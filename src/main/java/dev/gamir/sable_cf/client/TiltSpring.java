package dev.gamir.sable_cf.client;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.CfMath;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A second-order spring on a rotation, integrated in rotation-vector space.
 *
 * <p>Not a lerp. An exponential lerp towards a target has no momentum, so it is either sluggish or
 * twitchy and there is no setting in between - which is exactly the trade "snappy but smooth"
 * refuses to accept. A damped spring has both a stiffness and a damping ratio, and at a damping
 * ratio of 1 (critical) it gives the fastest possible approach with mathematically zero overshoot.
 * That is the actual definition of crisp without wobble.</p>
 *
 * <p>Integrated in sub-steps of at most 1/60 s so that a frame spike cannot make the explicit
 * integrator ring, and the angular velocity is clamped so a contraption that snaps 180 degrees in
 * one tick makes the camera lean over rather than whip.</p>
 */
public final class TiltSpring {

    private static final float MAX_SUB_STEP = 1.0f / 60.0f;

    private static final float RESIDUAL_EPSILON = 1.0e-3f;

    private final Quaternionf current = new Quaternionf();
    private final Vector3f angularVelocity = new Vector3f();

    public Quaternionf step(final Quaternionf target, final float deltaTicks) {
        // Clamped: a loading hitch must not be integrated as a real second of motion.
        float remaining = Math.min(Math.max(deltaTicks, 0.0f), 4.0f) / 20.0f;

        if (remaining <= 0.0f || !Float.isFinite(remaining)) {
            return new Quaternionf(this.current);
        }

        final float naturalFrequency = CfConfig.CAMERA_RESPONSE.get().floatValue();
        final float damping = CfConfig.CAMERA_DAMPING.get().floatValue();
        final float slew = (float) Math.toRadians(CfConfig.CAMERA_SLEW_DEG_PER_S.get());

        while (remaining > 0.0f) {
            final float dt = Math.min(remaining, MAX_SUB_STEP);
            remaining -= dt;

            // The rotation that takes current to target, as a plain 3-vector.
            final Vector3f error = CfMath.log(
                    new Quaternionf(target).mul(new Quaternionf(this.current).invert()));

            final Vector3f acceleration = new Vector3f(error).mul(naturalFrequency * naturalFrequency)
                    .sub(new Vector3f(this.angularVelocity).mul(2.0f * damping * naturalFrequency));

            this.angularVelocity.add(acceleration.mul(dt));
            CfMath.clampAngle(this.angularVelocity, slew);

            if (!this.angularVelocity.isFinite()) {
                this.angularVelocity.zero();
                this.current.identity();
                break;
            }

            this.current.set(CfMath.exp(new Vector3f(this.angularVelocity).mul(dt)).mul(this.current))
                    .normalize();
        }

        return new Quaternionf(this.current);
    }

    /** True while there is still tilt or motion left to unwind. */
    public boolean hasResidual() {
        return CfMath.log(this.current).length() > RESIDUAL_EPSILON
                || this.angularVelocity.length() > RESIDUAL_EPSILON;
    }
}
