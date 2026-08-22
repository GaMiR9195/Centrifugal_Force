package dev.gamir.sable_cf.client;

import com.playsi.aero_cam_sync.api.TiltContext;
import com.playsi.aero_cam_sync.api.TiltSource;
import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import dev.gamir.sable_cf.physics.CfMath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Where the camera thinks down is.
 *
 * <h2>What the camera is allowed to know</h2>
 *
 * <p>Exactly one thing: the plane the body has committed to, and how much of a wall-walker the
 * player currently is. Both come from {@link BodyFrame}, which means the camera cannot disagree
 * with the hitbox - they are two readings of the same decision, not two decisions that have to be
 * kept in step. The old camera derived its own target from the felt-gravity vector, which is why
 * it drifted away from the body in corners and lagged behind it in transitions.</p>
 *
 * <h2>Lively, but interpolated</h2>
 *
 * <p>Three things together, and it needs all three:</p>
 *
 * <ol>
 *   <li>The target is the <b>committed plane</b>, so it is a step function - it does not wobble
 *       when the forces wobble, and there is nothing for the smoothing to chase.</li>
 *   <li>{@link TiltSpring} is second order and critically damped, so the camera leans out with
 *       momentum and arrives without bouncing. The old exponential smoothing plus dead band did
 *       the opposite: fastest at the start, slowest at the end, and dead until a threshold
 *       released it in one lump.</li>
 *   <li>The target is extrapolated forward by {@code camera.lead_seconds}. A spring is always
 *       slightly behind a moving target; leading it by a fixed, small amount cancels most of that
 *       without adding any of the overshoot a lower damping would.</li>
 * </ol>
 *
 * <p>{@code camera.pitch_response} then takes a share off the vertical component only, because
 * vertical camera motion is what makes people motion-sick and roll is what makes a ride read as a
 * ride. It is applied to the spring's error rather than to its output, so the spring stays
 * critically damped instead of permanently fighting a scaled-down result.</p>
 *
 * <h2>Claiming frames</h2>
 *
 * <p>Winning a frame in ACS means owning the crosshair, the reach rays, projectile direction and
 * what the server is told - so this declines every frame it has nothing to say about, and keeps
 * claiming only while the spring is still unwinding back to level. Letting go mid-lean would hand
 * ACS a camera halfway over and produce exactly the snap this file exists to avoid.</p>
 */
public final class CfTiltSource implements TiltSource {

    /** Hard ceiling on how far ahead the lead may extrapolate, radians. */
    private static final float MAX_LEAD = (float) Math.toRadians(45.0);

    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private final TiltSpring spring = new TiltSpring();

    private final Quaternionf target = new Quaternionf();
    private final Quaternionf lastTarget = new Quaternionf();
    private final Quaternionf leadTarget = new Quaternionf();
    private final Quaternionf scratch = new Quaternionf();

    private final Vector3f pitchAxis = new Vector3f(1.0f, 0.0f, 0.0f);
    private final Vector3f work = new Vector3f();

    private boolean primed;

    @Override
    public boolean appliesTo(final TiltContext context) {
        if (!CfConfig.SPEC.isLoaded() || !CfConfig.CAMERA_ENABLED.get()) {
            return false;
        }

        final BodyFrame frame = frameOf(context.player());

        if (frame == null) {
            return false;
        }

        // Keep the frame while unwinding, or the camera would be dropped mid-lean.
        return frame.stick() > 0.0 || !this.spring.settled();
    }

    @Override
    public Quaternionf tilt(final TiltContext context) {
        final Player player = context.player();
        final BodyFrame frame = frameOf(player);

        if (frame == null) {
            return null;
        }

        final float dt = Math.max(1.0e-4f, Math.min(0.25f, context.deltaTicks() * (float) CfConfig.TICK));

        this.buildTarget(frame);
        this.applyLead(dt);
        this.updatePitchAxis(player, context.partialTick());

        return this.spring.advance(
                this.leadTarget,
                dt,
                (float) CfConfig.CAMERA_RESPONSE.get(),
                (float) CfConfig.CAMERA_DAMPING.get(),
                (float) Math.toRadians(CfConfig.CAMERA_SLEW_DEG_PER_S.get()),
                this.pitchAxis,
                (float) CfConfig.clamp01(CfConfig.CAMERA_PITCH_RESPONSE.get()));
    }

    /**
     * The orientation the camera is aiming at: the committed plane, owned to the extent the player
     * has earned it, plus a small honest lean towards where the forces actually point.
     */
    private void buildTarget(final BodyFrame frame) {
        this.target.identity();

        final double stick = CfConfig.clamp01(frame.stick());

        if (stick <= 0.0) {
            return;
        }

        final Vector3dc normal = frame.planeNormal();

        // Optional relief for players who do not want the full inversion at the top of a loop.
        // Off by default: the request was to be able to do the loop, and turning the camera over is
        // most of what makes hanging upside down read as hanging upside down.
        final double inversion = CfConfig.clamp01(-normal.y());
        final double suppression = 1.0 - CfConfig.CAMERA_LOOP_SUPPRESSION.get() * inversion;

        final double amount = CfConfig.clamp01(
                stick * CfConfig.CAMERA_AMOUNT.get() * Math.max(0.0, suppression));

        if (amount > 0.0) {
            final Quaterniondc plane = frame.plane().rotation();

            this.scratch.set(
                    (float) plane.x(), (float) plane.y(), (float) plane.z(), (float) plane.w())
                    .normalize();

            this.target.identity().slerp(this.scratch, (float) amount).normalize();
        }

        // A small extra lean towards felt-down. The plane is where you are standing; this is where
        // the ride is actually pulling. Capped hard, because the whole point of committing to a
        // plane was to stop the camera following a vector that moves every tick.
        final Vector3dc apparent = frame.state().apparent;
        final Vector3d feltUp = new Vector3d(apparent).negate();

        if (feltUp.lengthSquared() < 1.0e-8 || !feltUp.isFinite()) {
            return;
        }

        feltUp.normalize();

        this.work.set((float) normal.x(), (float) normal.y(), (float) normal.z());

        if (this.work.lengthSquared() < 1.0e-8) {
            return;
        }

        this.work.normalize();

        final Vector3f felt = new Vector3f((float) feltUp.x, (float) feltUp.y, (float) feltUp.z);

        final Vector3f lean = CfMath.log(this.scratch.rotationTo(this.work, felt));

        lean.mul((float) (stick * CfConfig.CAMERA_LEAN.get()));

        CfMath.clampAngle(lean, (float) Math.toRadians(CfConfig.CAMERA_LEAN_MAX_DEG.get()));

        if (!lean.isFinite()) {
            return;
        }

        this.target.premul(CfMath.exp(lean)).normalize();

        final Vector3f total = CfMath.log(this.target);

        CfMath.clampAngle(total, (float) Math.toRadians(CfConfig.CAMERA_MAX_TILT_DEG.get()));

        this.target.set(CfMath.exp(total));
    }

    /**
     * Aims a fixed time ahead of where the target is going.
     *
     * <p>A spring lags a moving target by roughly {@code 2 * zeta / omega} seconds, and that lag is
     * predictable, so it can simply be subtracted. Doing it this way rather than by lowering the
     * damping is the difference between a camera that keeps up and a camera that overshoots and
     * comes back - both look "faster" in a still frame and only one of them is comfortable.</p>
     */
    private void applyLead(final float dt) {
        final float lead = (float) CfConfig.CAMERA_LEAD.get();

        if (!this.primed || lead <= 0.0f) {
            this.leadTarget.set(this.target);
            this.lastTarget.set(this.target);
            this.primed = true;
            return;
        }

        final Quaternionf delta = new Quaternionf(this.lastTarget).conjugate().premul(this.target);
        final Vector3f rate = CfMath.log(delta).div(dt);

        if (!rate.isFinite()) {
            this.leadTarget.set(this.target);
            this.lastTarget.set(this.target);
            return;
        }

        rate.mul(lead);

        CfMath.clampAngle(rate, MAX_LEAD);

        this.leadTarget.set(this.target).premul(CfMath.exp(rate)).normalize();
        this.lastTarget.set(this.target);
    }

    /** The axis the player perceives as pitch: their own right, in world space. */
    private void updatePitchAxis(final Player player, final float partialTick) {
        final Vec3 view = player.getViewVector(partialTick);

        this.pitchAxis.set((float) view.x, (float) view.y, (float) view.z).cross(WORLD_UP);

        if (this.pitchAxis.lengthSquared() < 1.0e-6f) {
            this.pitchAxis.set(1.0f, 0.0f, 0.0f);
            return;
        }

        this.pitchAxis.normalize();
    }

    private static BodyFrame frameOf(final Player player) {
        if (!(player instanceof BodyFrameHolder holder)) {
            return null;
        }

        return holder.sable_cf$bodyFrameOrNull();
    }
}
