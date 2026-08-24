package com.playsi.centrifugal_force.internal;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

final class AdhesionMath {
    static final Vector3dc UP = new Vector3d(0.0, 1.0, 0.0);

    /** Every local face that may become a floor, which is what allows a full 360 degree circle. */
    static final Vector3dc[] AXES = {
            new Vector3d(0.0, 1.0, 0.0),
            new Vector3d(0.0, -1.0, 0.0),
            new Vector3d(1.0, 0.0, 0.0),
            new Vector3d(-1.0, 0.0, 0.0),
            new Vector3d(0.0, 0.0, 1.0),
            new Vector3d(0.0, 0.0, -1.0),
    };

    static double smoothStep(final double value) {
        final double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * (3.0 - 2.0 * t);
    }

    static Vector3d snapAxis(final Vector3dc value, final Vector3d dest) {
        final double ax = Math.abs(value.x());
        final double ay = Math.abs(value.y());
        final double az = Math.abs(value.z());
        if (ax >= ay && ax >= az) return dest.set(Math.copySign(1.0, value.x()), 0.0, 0.0);
        if (ay >= az) return dest.set(0.0, Math.copySign(1.0, value.y()), 0.0);
        return dest.set(0.0, 0.0, Math.copySign(1.0, value.z()));
    }

    static String axisLabel(final Vector3dc value) {
        final double ax = Math.abs(value.x());
        final double ay = Math.abs(value.y());
        final double az = Math.abs(value.z());
        if (ax >= ay && ax >= az) return value.x() >= 0.0 ? "+X" : "-X";
        if (ay >= az) return value.y() >= 0.0 ? "+Y" : "-Y";
        return value.z() >= 0.0 ? "+Z" : "-Z";
    }

    /**
     * Minimal rotation taking world up onto {@code target}, with no twist about the target axis.
     *
     * <p>This is the whole reason the camera stays put. A sub-level's own orientation carries its
     * yaw and spin, so composing it directly would turn the view the instant the player attaches.
     * Keeping only the swing means the returned rotation is non-identity exactly when the surface
     * really is tilted, so the view turns only as far as the hitbox is actually tilted.
     */
    static Quaterniond swingFromUp(final Vector3dc target, final Vector3dc fallbackAxis, final Quaterniond dest) {
        final double dot = Math.max(-1.0, Math.min(1.0, UP.dot(target)));
        if (dot > 0.999999) return dest.identity();

        if (dot < -0.999999) {
            final Vector3d axis = new Vector3d(fallbackAxis);
            axis.fma(-axis.dot(UP), UP);
            if (axis.lengthSquared() < 1.0e-9) axis.set(1.0, 0.0, 0.0);
            axis.normalize();
            return dest.fromAxisAngleRad(axis.x, axis.y, axis.z, Math.PI);
        }

        final Vector3d axis = new Vector3d(UP).cross(target);
        final double length = axis.length();
        if (length < 1.0e-12) return dest.identity();
        axis.div(length);
        return dest.fromAxisAngleRad(axis.x, axis.y, axis.z, Math.atan2(length, dot));
    }

    private AdhesionMath() {}
}
