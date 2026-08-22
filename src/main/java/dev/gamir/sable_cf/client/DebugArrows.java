package dev.gamir.sable_cf.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import dev.gamir.sable_cf.physics.ForceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The debug overlay: flat, camera-facing arrows with 2D triangular heads.
 *
 * <p>Billboarded on purpose. A 3D cone tells you almost nothing about a vector pointed near the
 * camera - it just becomes a blob - whereas a flat arrow whose face is always turned towards you
 * reads at the same size and the same shape from every angle. Same reason old sprite-based games
 * drew flat props.</p>
 *
 * <p>Everything is drawn in camera-relative space and the pose stack is left alone. That is not a
 * shortcut: sub-level plots live millions of blocks out, and world-space floats lose precision so
 * badly out there that the arrows would visibly jitter. Subtracting the camera position in double
 * first keeps every coordinate small.</p>
 *
 * <h2>Drawing in front of the player model</h2>
 *
 * <p>The arrows were disappearing into the player's own body, and the reason is depth: they are
 * drawn after the model, but at the same place as it, so the model wins the depth test. There are
 * two ways to fix that and only one of them is honest here.</p>
 *
 * <p>Disabling the depth test does not work by hand. {@link RenderType#debugQuads()} carries a
 * LEQUAL depth shard, and a render type re-applies its own state in {@code setupRenderState()} when
 * the batch is flushed - so a {@code RenderSystem.disableDepthTest()} call from here is overwritten
 * a moment later. Building a render type with {@code NO_DEPTH_TEST} instead needs
 * {@code RenderType.create}, which is {@code protected static} and therefore needs an access
 * transformer. That is a real option, but it is a build-level change for a cosmetic debug feature,
 * so it is deliberately not taken - see {@code docs/UPSTREAM.md}.</p>
 *
 * <p>What is done instead is to bias the whole arrow cluster towards the camera, which is the same
 * trick vanilla uses for its own layered overlays. It puts the geometry in front of the player
 * model - the actual complaint - without touching the render pipeline. Arrows are still occluded by
 * <i>world</i> geometry, and that is a known limitation rather than an oversight.</p>
 *
 * <p>Transparency is genuine alpha blending, not a stipple: {@code debugQuads} already uses
 * {@code TRANSLUCENT_TRANSPARENCY}, so the only thing that was missing was an alpha value worth
 * setting. It comes from {@code debug.alpha} now.</p>
 */
public final class DebugArrows {

    // RGB only - alpha comes from the config so one setting changes all of them together.
    private static final int COLOR_AIR_VELOCITY = 0xFFFFFF;
    private static final int COLOR_CENTRIFUGAL = 0xFF9628;
    private static final int COLOR_DRAG = 0x3CC8FF;
    private static final int COLOR_APPARENT = 0xDC50FF;
    private static final int COLOR_NORMAL = 0x5AE678;

    /** Length of the surface-normal arrow. Fixed - it is a direction, its magnitude means nothing. */
    private static final float NORMAL_LENGTH = 0.9f;

    /**
     * Below this the velocity arrow is not drawn at all, m/s. Roughly a slow walk: standing still
     * should look like standing still, not like a twitching stub.
     */
    private static final double DEADZONE_VELOCITY = 0.5;

    /** Below this an acceleration arrow is not drawn, as a fraction of gravity. */
    private static final double DEADZONE_ACCEL_G = 0.10;

    /** How far towards the camera to push the arrows so they clear the player model, blocks. */
    private static final float CAMERA_BIAS = 0.35f;

    // Smoothed in raw physical units - m/s and m/s^2 - so the thresholds below can be expressed as
    // real quantities. Smoothing the already-scaled vectors made every threshold depend on the
    // display scale, which is exactly backwards.
    private final Vector3f airVelocity = new Vector3f();
    private final Vector3f centrifugal = new Vector3f();
    private final Vector3f drag = new Vector3f();
    private final Vector3f apparent = new Vector3f();
    private final Vector3f normal = new Vector3f();

    private long lastNanos;

    @SubscribeEvent
    public void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        if (!CfConfig.SPEC.isLoaded() || !CfConfig.DEBUG_OVERLAY.get()) {
            this.lastNanos = 0L;
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;

        if (player == null) {
            return;
        }

        if (!(player instanceof BodyFrameHolder holder)) {
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();

        if (frame == null) {
            return;
        }

        final ForceState state = frame.state();

        // Smoothing runs on wall-clock time, not ticks, so it looks the same at 30 and at 300 fps.
        final long now = System.nanoTime();
        final float dt = this.lastNanos == 0L
                ? 0.0f
                : Math.min((now - this.lastNanos) / 1.0e9f, 0.25f);
        this.lastNanos = now;

        final float blend = blendFactor(dt);

        approach(this.airVelocity, state.active ? state.airVelocity : null, blend);
        approach(this.centrifugal, state.active ? state.centrifugal : null, blend);
        approach(this.drag, state.active ? state.drag : null, blend);
        approach(this.apparent, state.active ? state.apparent : null, blend);
        approach(this.normal, state.active ? state.normal : null, blend);

        final float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        final Vec3 camera = event.getCamera().getPosition();

        // Chest height: an arrow starting at the feet is half buried in the deck.
        final Vec3 anchorVec = player.getPosition(partialTick)
                .add(0.0, player.getBbHeight() * 0.62, 0.0)
                .subtract(camera);

        final Vector3f anchor = new Vector3f(
                (float) anchorVec.x, (float) anchorVec.y, (float) anchorVec.z);

        // Camera-relative space, so the camera sits at the origin and the direction towards it is
        // simply -anchor.
        final Vector3f toCamera = new Vector3f(anchor).negate();
        final float distance = toCamera.length();

        if (distance > 1.0e-4f) {
            toCamera.div(distance);

            // Never bias past the camera itself, or in first person the arrows end up behind the
            // near plane and vanish - which would be a worse version of the bug being fixed.
            anchor.add(new Vector3f(toCamera).mul(Math.min(CAMERA_BIAS, distance * 0.5f)));
        } else {
            toCamera.set(0.0f, 0.0f, 1.0f);
        }

        final PoseStack poseStack = event.getPoseStack();
        final Matrix4f matrix = poseStack.last().pose();

        final int alpha = Math.min(255, Math.max(13,
                (int) Math.round(CfConfig.DEBUG_ALPHA.get() * 255.0)));

        final double accelScale = CfConfig.DEBUG_ACCEL_SCALE.get();
        final double velocityScale = CfConfig.DEBUG_VELOCITY_SCALE.get();
        final double accelDeadzone = DEADZONE_ACCEL_G * CfConfig.GRAVITY;

        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        final VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());

        // The normal is a direction marker, so it is drawn at a fixed length with no dead zone.
        arrow(consumer, matrix, anchor, toCamera, this.normal,
                NORMAL_LENGTH / Math.max(1.0e-4, this.normal.length()), 0.0, COLOR_NORMAL, alpha);

        arrow(consumer, matrix, anchor, toCamera, this.apparent,
                accelScale, accelDeadzone, COLOR_APPARENT, alpha);
        arrow(consumer, matrix, anchor, toCamera, this.centrifugal,
                accelScale, accelDeadzone, COLOR_CENTRIFUGAL, alpha);
        arrow(consumer, matrix, anchor, toCamera, this.drag,
                accelScale, accelDeadzone, COLOR_DRAG, alpha);
        arrow(consumer, matrix, anchor, toCamera, this.airVelocity,
                velocityScale, DEADZONE_VELOCITY, COLOR_AIR_VELOCITY, alpha);

        buffers.endBatch(RenderType.debugQuads());
    }

    /**
     * Exponential smoothing expressed as a half-life, so the config number means something real:
     * "half the remaining error disappears every N milliseconds", regardless of framerate.
     */
    private static float blendFactor(final float dt) {
        final int halfLifeMs = CfConfig.DEBUG_SMOOTHING_MS.get();

        if (halfLifeMs <= 0 || dt <= 0.0f) {
            return 1.0f;
        }

        return 1.0f - (float) Math.pow(2.0, -dt / (halfLifeMs / 1000.0f));
    }

    private static void approach(final Vector3f store, final Vector3d target, final float blend) {
        final float x = target == null ? 0.0f : (float) target.x;
        final float y = target == null ? 0.0f : (float) target.y;
        final float z = target == null ? 0.0f : (float) target.z;

        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            store.zero();
            return;
        }

        store.lerp(new Vector3f(x, y, z), blend);
    }

    /**
     * Three length bands, which is the whole readability trick.
     *
     * <p>Below the dead zone there is no arrow, so idling looks idle. Above it the arrow is never
     * shorter than {@code min_length}, because a proportional arrow for a small force is a dot you
     * cannot read a direction from - and direction is most of the information. Past that, length
     * tracks magnitude up to {@code max_length}, which keeps a violent spike from drawing a
     * kilometre-long arrow across the world.</p>
     */
    private static void arrow(final VertexConsumer consumer, final Matrix4f matrix,
                              final Vector3f origin, final Vector3f toCamera,
                              final Vector3f vector, final double scale, final double deadzone,
                              final int rgb, final int alpha) {

        final double magnitude = vector.length();

        if (!Double.isFinite(magnitude) || magnitude <= deadzone || magnitude < 1.0e-6) {
            return;
        }

        final double minLength = CfConfig.DEBUG_MIN_LENGTH.get();
        final double maxLength = CfConfig.DEBUG_MAX_LENGTH.get();

        final float length = (float) Math.min(maxLength, Math.max(minLength, magnitude * scale));

        if (length < 1.0e-4f) {
            return;
        }

        final Vector3f direction = new Vector3f(vector).div((float) magnitude);

        Vector3f side = new Vector3f(direction).cross(toCamera);

        if (side.lengthSquared() < 1.0e-8f) {
            // Arrow pointing straight at or away from the camera - it has no readable width, so any
            // perpendicular is as good as another.
            side = new Vector3f(direction).cross(new Vector3f(0.0f, 1.0f, 0.0f));

            if (side.lengthSquared() < 1.0e-8f) {
                side = new Vector3f(1.0f, 0.0f, 0.0f);
            }
        }

        side.normalize();

        final float headLength = Math.min(0.18f, length * 0.45f);
        final float shaftHalf = Math.min(0.022f, headLength * 0.22f);
        final float headHalf = headLength * 0.42f;

        final Vector3f tip = new Vector3f(direction).mul(length).add(origin);
        final Vector3f neck = new Vector3f(direction).mul(length - headLength).add(origin);

        final Vector3f shaftOffset = new Vector3f(side).mul(shaftHalf);
        final Vector3f headOffset = new Vector3f(side).mul(headHalf);

        quad(consumer, matrix,
                new Vector3f(origin).add(shaftOffset),
                new Vector3f(neck).add(shaftOffset),
                new Vector3f(neck).sub(shaftOffset),
                new Vector3f(origin).sub(shaftOffset),
                rgb, alpha);

        // Two vertices on the tip: a quad the GPU draws as a triangle.
        quad(consumer, matrix,
                new Vector3f(neck).add(headOffset),
                new Vector3f(neck).sub(headOffset),
                tip,
                tip,
                rgb, alpha);
    }

    private static void quad(final VertexConsumer consumer, final Matrix4f matrix,
                             final Vector3f a, final Vector3f b, final Vector3f c, final Vector3f d,
                             final int rgb, final int alpha) {
        final int red = (rgb >> 16) & 0xFF;
        final int green = (rgb >> 8) & 0xFF;
        final int blue = rgb & 0xFF;

        consumer.addVertex(matrix, a.x, a.y, a.z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, b.x, b.y, b.z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, c.x, c.y, c.z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, d.x, d.y, d.z).setColor(red, green, blue, alpha);
    }
}
