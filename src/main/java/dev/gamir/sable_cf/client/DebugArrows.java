package dev.gamir.sable_cf.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
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
 * <p>One {@link RenderType#debugQuads()} batch for all of it. Quads only, and an arrowhead is a
 * quad with its last two vertices collapsed onto the tip - a triangle, no second render type.</p>
 */
public final class DebugArrows {

    private static final int COLOR_AIR_VELOCITY = 0xE6FFFFFF;
    private static final int COLOR_CENTRIFUGAL = 0xE6FF9628;
    private static final int COLOR_DRAG = 0xE63CC8FF;
    private static final int COLOR_APPARENT = 0xE6DC50FF;
    private static final int COLOR_NORMAL = 0xB35AE678;

    /** Length of the surface-normal arrow. Fixed - it is a direction, its magnitude means nothing. */
    private static final float NORMAL_LENGTH = 0.9f;

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

        final ForceState state = CentrifugalHandler.STATE;

        // Smoothing runs on wall-clock time, not ticks, so it looks the same at 30 and at 300 fps.
        final long now = System.nanoTime();
        final float dt = this.lastNanos == 0L
                ? 0.0f
                : Math.min((now - this.lastNanos) / 1.0e9f, 0.25f);
        this.lastNanos = now;

        final float blend = blendFactor(dt);

        final float accelScale = CfConfig.DEBUG_ACCEL_SCALE.get().floatValue();
        final float velocityScale = CfConfig.DEBUG_VELOCITY_SCALE.get().floatValue();

        approach(this.airVelocity, state.active ? state.airVelocity : null, velocityScale, blend);
        approach(this.centrifugal, state.active ? state.centrifugal : null, accelScale, blend);
        approach(this.drag, state.active ? state.drag : null, accelScale, blend);
        approach(this.apparent, state.active ? state.apparent : null, accelScale, blend);
        approach(this.normal, state.active ? state.normal : null, NORMAL_LENGTH, blend);

        final float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        final Vec3 camera = event.getCamera().getPosition();

        // Chest height: an arrow starting at the feet is half buried in the deck.
        final Vec3 anchorVec = player.getPosition(partialTick)
                .add(0.0, player.getBbHeight() * 0.62, 0.0)
                .subtract(camera);

        final Vector3f anchor = new Vector3f(
                (float) anchorVec.x, (float) anchorVec.y, (float) anchorVec.z);

        final PoseStack poseStack = event.getPoseStack();
        final Matrix4f matrix = poseStack.last().pose();

        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        final VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());

        arrow(consumer, matrix, anchor, this.normal, COLOR_NORMAL);
        arrow(consumer, matrix, anchor, this.apparent, COLOR_APPARENT);
        arrow(consumer, matrix, anchor, this.centrifugal, COLOR_CENTRIFUGAL);
        arrow(consumer, matrix, anchor, this.drag, COLOR_DRAG);
        arrow(consumer, matrix, anchor, this.airVelocity, COLOR_AIR_VELOCITY);

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

    private static void approach(final Vector3f store, final Vector3d target,
                                 final float scale, final float blend) {
        final float x = target == null ? 0.0f : (float) target.x * scale;
        final float y = target == null ? 0.0f : (float) target.y * scale;
        final float z = target == null ? 0.0f : (float) target.z * scale;

        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            store.zero();
            return;
        }

        store.lerp(new Vector3f(x, y, z), blend);
    }

    private static void arrow(final VertexConsumer consumer, final Matrix4f matrix,
                              final Vector3f origin, final Vector3f vector, final int argb) {
        final float length = vector.length();

        if (!Float.isFinite(length) || length < 0.05f) {
            return;
        }

        final Vector3f direction = new Vector3f(vector).div(length);

        // Camera-relative space, so the camera sits at the origin and the direction towards it is
        // simply -origin.
        final Vector3f toCamera = new Vector3f(origin).negate();

        if (toCamera.lengthSquared() < 1.0e-6f) {
            toCamera.set(0.0f, 0.0f, 1.0f);
        }

        toCamera.normalize();

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
                argb);

        // Two vertices on the tip: a quad the GPU draws as a triangle.
        quad(consumer, matrix,
                new Vector3f(neck).add(headOffset),
                new Vector3f(neck).sub(headOffset),
                tip,
                tip,
                argb);
    }

    private static void quad(final VertexConsumer consumer, final Matrix4f matrix,
                             final Vector3f a, final Vector3f b, final Vector3f c, final Vector3f d,
                             final int argb) {
        final int alpha = (argb >>> 24) & 0xFF;
        final int red = (argb >> 16) & 0xFF;
        final int green = (argb >> 8) & 0xFF;
        final int blue = argb & 0xFF;

        consumer.addVertex(matrix, a.x, a.y, a.z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, b.x, b.y, b.z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, c.x, c.y, c.z).setColor(red, green, blue, alpha);
        consumer.addVertex(matrix, d.x, d.y, d.z).setColor(red, green, blue, alpha);
    }
}
