package dev.gamir.sable_cf.physics;

import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Rotation-vector helpers. Small, but the whole camera depends on them being right.
 *
 * <p>A rotation vector (axis times angle, one plain 3-vector) is the representation that makes a
 * rotational spring possible at all: you can add them, scale them, and clamp their length, none of
 * which is true of quaternions or of Euler angles. {@link #log} goes quaternion -> rotation vector,
 * {@link #exp} goes back.</p>
 */
public final class CfMath {

    private static final float EPSILON = 1.0e-7f;

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
            // Small-angle limit: angle ~ 2 * sine, and the axis is undefined but irrelevant.
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

    private CfMath() {
    }
}
