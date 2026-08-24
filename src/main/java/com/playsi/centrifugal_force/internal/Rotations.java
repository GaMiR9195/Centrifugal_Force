package com.playsi.centrifugal_force.internal;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

final class Rotations {
    static final Vector3dc UP = new Vector3d(0.0, 1.0, 0.0);

    /** Every local face that can become a floor. */
    static final Vector3dc[] AXES = {
            new Vector3d(1.0, 0.0, 0.0), new Vector3d(-1.0, 0.0, 0.0),
            new Vector3d(0.0, 1.0, 0.0), new Vector3d(0.0, -1.0, 0.0),
            new Vector3d(0.0, 0.0, 1.0), new Vector3d(0.0, 0.0, -1.0),
    };

    private Rotations() {}

    static double ease(final double progress) {
        final double t = Math.max(0.0, Math.min(1.0, progress));
        return t * t * (3.0 - 2.0 * t);
    }

    /** Shortest rotation taking {@code from} onto {@code to}. The two must not be opposed. */
    static Quaterniond swing(final Vector3dc from, final Vector3dc to, final Quaterniond dest) {
        final Vector3d axis = new Vector3d(from).cross(to);
        final double length = axis.length();
        if (length < 1.0e-12) return dest.identity();
        return dest.fromAxisAngleRad(axis.x / length, axis.y / length, axis.z / length,
                Math.atan2(length, from.dot(to)));
    }

    /** {@code angle} radians about {@code axis}, applied on top of {@code base}. */
    static Quaterniond rotateAbout(final Vector3dc axis, final double angle, final Quaterniondc base,
                                   final Quaterniond dest) {
        return new Quaterniond().fromAxisAngleRad(axis.x(), axis.y(), axis.z(), angle).mul(base, dest);
    }

    static String label(final Vector3dc axis) {
        if (axis.x() != 0.0) return axis.x() > 0.0 ? "+X" : "-X";
        if (axis.y() != 0.0) return axis.y() > 0.0 ? "+Y" : "-Y";
        return axis.z() > 0.0 ? "+Z" : "-Z";
    }
}
