package dev.gamir.sable_cf.physics;

import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * Per-tick angular state of one sub-level, derived from nothing but its two poses.
 *
 * <p>Sable does expose the real angular velocity off the rigid body, but only on the server and
 * only through the physics system. The pose difference is available on both sides, is what Sable's
 * own client-side {@code getVelocity} uses, and is exactly the number the player actually saw move
 * - so it is the better source here, not a fallback.</p>
 *
 * <p>omega comes out in rad/s, alpha in rad/s^2, both in world space.</p>
 */
public final class FrameSample {

    /**
     * Below this the delta quaternion is treated as identity.
     *
     * <p>Not an optimisation. Converting a near-identity quaternion to axis-angle divides by a
     * vanishing sine and produces NaN, and one NaN in the velocity is a permanently frozen player.
     * |w| within 1e-10 of 1 is a rotation below about 2e-5 rad/tick, far under anything visible.
     * Sure Footing learned this one the hard way on a completely static contraption.</p>
     */
    private static final double IDENTITY_EPSILON = 1.0e-10;

    private final Quaterniond lastOrientation = new Quaterniond();
    private final Vector3d omega = new Vector3d();
    private final Vector3d previousOmega = new Vector3d();
    private final Vector3d alpha = new Vector3d();

    private UUID anchor;
    private boolean valid;

    public void reset() {
        this.anchor = null;
        this.valid = false;
        this.omega.zero();
        this.previousOmega.zero();
        this.alpha.zero();
    }

    /**
     * Samples the sub-level. The first call after (re)anchoring produces no rates - there is no
     * previous pose to difference against, and inventing one is how you get a one-tick kick every
     * time the player steps onto a contraption.
     */
    public void sample(final SubLevel subLevel) {
        final Quaterniondc orientation = subLevel.logicalPose().orientation();
        final UUID id = subLevel.getUniqueId();

        if (this.anchor != null && this.anchor.equals(id)) {
            final Quaterniond delta = new Quaterniond(orientation)
                    .mul(new Quaterniond(this.lastOrientation).invert())
                    .normalize();

            this.previousOmega.set(this.omega);

            if (Math.abs(delta.w) >= 1.0 - IDENTITY_EPSILON) {
                this.omega.zero();
            } else {
                final AxisAngle4d axisAngle = new AxisAngle4d().set(delta);
                double angle = axisAngle.angle;

                // JOML reports the angle in [0, 2pi). Past half a turn the short way round is the
                // other direction, and without this a fast flip reads as a near-full-speed spin
                // the wrong way - which then points the centrifugal vector inwards.
                if (angle > Math.PI) {
                    angle -= 2.0 * Math.PI;
                }

                this.omega.set(axisAngle.x, axisAngle.y, axisAngle.z);

                if (this.omega.lengthSquared() > 1.0e-18) {
                    this.omega.normalize().mul(angle * 20.0);
                } else {
                    this.omega.zero();
                }
            }

            if (!this.omega.isFinite()) {
                this.omega.zero();
            }

            this.alpha.set(this.omega).sub(this.previousOmega).mul(20.0);

            if (!this.alpha.isFinite()) {
                this.alpha.zero();
            }

            this.valid = true;
        } else {
            this.omega.zero();
            this.previousOmega.zero();
            this.alpha.zero();
            this.valid = false;
        }

        this.lastOrientation.set(orientation);
        this.anchor = id;
    }

    public boolean isValid() {
        return this.valid;
    }

    /** Angular velocity, rad/s, world space. */
    public Vector3dc omega() {
        return this.omega;
    }

    /** Angular acceleration, rad/s^2, world space. */
    public Vector3dc alpha() {
        return this.alpha;
    }
}
