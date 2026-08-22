package dev.gamir.sable_cf.physics;

import org.joml.AxisAngle4f;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Rotation-vector helpers. Small, but the body, the hitbox and the camera all rest on them.
 *
 * <p>A rotation vector - axis times angle, one plain 3-vector - is the representation that makes a
 * rotational spring possible at all: you can add them, scale them and clamp their length, none of
 * which is true of quaternions or of Euler angles. {@link #log} goes quaternion to rotation vector,
 * {@link #exp} comes back, and {@link #approach} is the one operation the rest of the mod actually
 * wants: "move this orientation a fraction of the way towards that one, but no faster than X".</p>
 */
public final class CfMath {

    private static final float EPSILON = 1.0e-7f;
    private static final double EPSILON_D = 1.0e-12;

    /**
     * Quaternion to rotation vector, always taking the short way round.
     *
     * <p>The {@code w < 0} flip is not cosmetic: q and -q are the same rotation, but their naive
     * logs are 2 pi apart, so without it a spring asked to travel 1 degree occasionally decides to
     * travel 359 instead.</p>
     */
    public static Vector3f log(final Quaternionf q) {
        final Quaternionf n = new Quaternionf(q).normalize();

        if (n.w < 0.0f) {
            n.set(-n.x, -n.y, -n.z, -n.w);
        }

        final float sine = (float) Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z);

        if (sine < EPSILON) {
            return new Vector3f(n.x * 2.0f, n.y * 2.0f, n.z * 2.0f);
        }

        final float angle = 2.0f * (float) Math.atan2(sine, n.w);
        return new Vector3f(n.x, n.y, n.z).mul(angle / sine);
    }

    /** Rotation vector to quaternion. */
    public static Quaternionf exp(final Vector3f rotationVector) {
        final float angle = rotationVector.length();

        if (angle < EPSILON || !Float.isFinite(angle)) {
            return new Quaternionf();
        }

        return new Quaternionf(new AxisAngle4f(angle,
                rotationVector.x / angle, rotationVector.y / angle, rotationVector.z / angle));
    }

    /** Clamps a rotation vector's angle in place, keeping its axis. */
    public static Vector3f clampAngle(final Vector3f rotationVector, final float maxRadians) {
        final float angle = rotationVector.length();

        if (angle > maxRadians && angle > EPSILON) {
            rotationVector.mul(maxRadians / angle);
        }

        return rotationVector;
    }

    // ------------------------------------------------------------------ double precision

    /**
     * Quaternion to rotation vector, in double.
     *
     * <p>Used for angular velocity, where float is genuinely not enough: one tick of a 0.2 rad/s
     * spin is 0.01 rad, and taking a difference of two of those in float leaves about three
     * significant digits to build an angular acceleration out of.</p>
     */
    public static Vector3d log(final Quaterniondc q, final Vector3d dest) {
        final Quaterniond n = new Quaterniond(q).normalize();

        if (n.w < 0.0) {
            n.set(-n.x, -n.y, -n.z, -n.w);
        }

        final double sine = Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z);

        if (sine < EPSILON_D) {
            return dest.set(n.x * 2.0, n.y * 2.0, n.z * 2.0);
        }

        final double angle = 2.0 * Math.atan2(sine, n.w);

        return dest.set(n.x, n.y, n.z).mul(angle / sine);
    }

    /** Rotation vector to quaternion, in double. */
    public static Quaterniond exp(final Vector3dc rotationVector, final Quaterniond dest) {
        final double angle = rotationVector.length();

        if (angle < EPSILON_D || !Double.isFinite(angle)) {
            return dest.identity();
        }

        return dest.setAngleAxis(angle,
                rotationVector.x() / angle, rotationVector.y() / angle, rotationVector.z() / angle);
    }

    /** Clamps a rotation vector's angle in place, keeping its axis. */
    public static Vector3d clampAngle(final Vector3d rotationVector, final double maxRadians) {
        final double angle = rotationVector.length();

        if (angle > maxRadians && angle > EPSILON_D) {
            rotationVector.mul(maxRadians / angle);
        }

        return rotationVector;
    }

    /**
     * Moves {@code current} a fraction of the way towards {@code target}, never turning faster than
     * {@code maxRadians} in this step.
     *
     * <p>Deliberately not {@code slerp}. Slerp cannot be rate limited without changing what its
     * parameter means, and a rate limit is the only thing standing between a contraption that snaps
     * 180 degrees in one tick and a player whose collision box does the same. Working through the
     * rotation vector gives an exponential approach <i>and</i> a hard speed cap in the same step,
     * and the two compose the way you would want: normally the half-life decides, and on the one
     * violent tick the cap does.</p>
     *
     * @return the angle actually turned, radians
     */
    public static double approach(final Quaterniond current, final Quaterniondc target,
                                  final double alpha, final double maxRadians) {

        if (!(alpha > 0.0)) {
            return 0.0;
        }

        // delta = target * current^-1, i.e. the world-frame rotation that takes one to the other.
        final Quaterniond delta = new Quaterniond(current).conjugate().premul(target);

        final Vector3d step = log(delta, new Vector3d());

        if (!step.isFinite()) {
            return 0.0;
        }

        step.mul(Math.min(1.0, alpha));
        clampAngle(step, Math.max(0.0, maxRadians));

        final double turned = step.length();

        if (turned < EPSILON_D) {
            return 0.0;
        }

        current.premul(exp(step, new Quaterniond())).normalize();

        return turned;
    }

    /** The rotation angle of a quaternion, radians, always in [0, pi]. */
    public static double angleOf(final Quaterniondc q) {
        final double w = Math.min(1.0, Math.abs(q.w()));

        return 2.0 * Math.acos(w);
    }

    private CfMath() {
    }
}
