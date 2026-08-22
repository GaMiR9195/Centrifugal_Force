package dev.gamir.sable_cf.client;

import dev.gamir.sable_cf.physics.CfMath;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * A critically damped rotational spring. This is the whole of the camera's feel.
 *
 * <h2>Why a spring and not smoothing</h2>
 *
 * <p>The old camera was an exponential low-pass with a dead band in front of it, and both halves
 * of that are why it was reported as uncomfortable. A low-pass has no momentum, so it is fastest
 * at the moment the target moves and slowest as it arrives - the exact opposite of how a head
 * moves, and the reason it read as "laggy then sudden". The dead band then held the camera
 * perfectly still until the error crossed a threshold and released it all at once, which turns a
 * smooth ramp into a series of small jerks.</p>
 *
 * <p>A second-order spring has momentum. It leans out ahead, arrives with its velocity already
 * decaying, and settles without overshooting - which is what "lively but pleasantly interpolated"
 * means in practice. Response sets how quickly, damping sets whether it overshoots, and the two
 * are independent, so the camera can be made snappier without also being made bouncy. There is no
 * dead band at all: a critically damped spring is already still when its error is small.</p>
 *
 * <h2>Rotation vectors, and why the maths has to be done in them</h2>
 *
 * <p>Springs need addition and scaling, and quaternions have neither. Everything below runs on the
 * logarithm - axis times angle - where {@code error * k} and {@code velocity + accel * dt} mean
 * what they look like, and only the final step exponentiates back. {@link CfMath#log} takes the
 * short way round, so the camera never rolls 350 degrees to avoid rolling 10.</p>
 *
 * <h2>Substepping</h2>
 *
 * <p>An explicit integrator with a stiff spring goes unstable when the timestep gets long, and
 * frame times are not something a mod gets to choose - one 200 ms hitch would otherwise leave the
 * camera spinning. The step is subdivided so behaviour is identical at 30 fps and at 300, which is
 * also what the ACS docs ask of a source.</p>
 */
public final class TiltSpring {

    /** Longest integration step, seconds. Beyond this a stiff spring stops being stable. */
    private static final float MAX_SUB_STEP = 1.0f / 120.0f;

    /** Below this the spring is at rest and the camera can be handed back. */
    private static final float RESIDUAL_EPSILON = 1.0e-3f;

    private final Quaternionf current = new Quaternionf();

    /** Angular velocity as a rotation vector, world frame, rad/s. */
    private final Vector3f velocity = new Vector3f();

    private final Quaternionf scratch = new Quaternionf();
    private final Vector3f error = new Vector3f();
    private final Vector3f step = new Vector3f();

    public void reset() {
        this.current.identity();
        this.velocity.zero();
    }

    /**
     * Integrates towards {@code target} for {@code dt} seconds.
     *
     * @param response  natural frequency, rad/s. Higher is snappier.
     * @param damping   1.0 is critical. Below overshoots, above crawls in.
     * @param maxRate   hard ceiling on turn rate, rad/s. The one thing that is not negotiable -
     *                  it is what guarantees a contraption that snaps 180 degrees in a tick cannot
     *                  do the same to the player's head.
     * @param pitchAxis unit axis that counts as pitch, or null for none.
     * @param pitchGain how much of the pitch component of the error to act on, 0..1.
     */
    public Quaternionf advance(final Quaternionfc target, final float dt,
                                final float response, final float damping, final float maxRate,
                                final Vector3f pitchAxis, final float pitchGain) {

        if (!(dt > 0.0f) || !Float.isFinite(dt)) {
            return this.current;
        }

        final int steps = Math.max(1, Math.min(16, (int) Math.ceil(dt / MAX_SUB_STEP)));
        final float sub = dt / steps;

        final float omega = Math.max(0.1f, response);
        final float zeta = Math.max(0.05f, damping);

        for (int i = 0; i < steps; i++) {
            // error = log(target * current^-1), i.e. the world-frame turn still to be made.
            this.scratch.set(this.current).conjugate().premul(target);

            this.error.set(CfMath.log(this.scratch));

            if (!this.error.isFinite()) {
                this.error.zero();
            }

            // Comfort, not physics: vertical tilt is what makes people queasy, so the camera is
            // allowed to follow roll fully while taking only a share of pitch. Applied to the
            // ERROR rather than to the result, so the spring stays critically damped - scaling the
            // output would leave a permanent offset that the spring would keep pulling against.
            if (pitchAxis != null && pitchGain < 1.0f) {
                final float along = this.error.dot(pitchAxis);

                this.error.fma(-(1.0f - pitchGain) * along, pitchAxis);
            }

            // Critically damped second order: a = w^2 * x - 2*z*w * v
            this.velocity.fma(omega * omega * sub, this.error)
                    .fma(-2.0f * zeta * omega * sub, this.velocity);

            if (!this.velocity.isFinite()) {
                this.velocity.zero();
            }

            CfMath.clampAngle(this.velocity, maxRate);

            this.step.set(this.velocity).mul(sub);

            this.current.premul(CfMath.exp(this.step)).normalize();
        }

        return this.current;
    }

    /** True once the camera is level and still, so the frame can be handed back to ACS. */
    public boolean settled() {
        return CfMath.log(this.current).length() < RESIDUAL_EPSILON
                && this.velocity.length() < RESIDUAL_EPSILON;
    }

    public Quaternionf value() {
        return this.current;
    }
}
