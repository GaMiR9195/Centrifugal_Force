package com.playsi.centrifugal_force.internal;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One plane change, and for corners the exact path the pivot has to follow.
 *
 * <p>The oriented box is 0.6 x 1.8 x 0.6 and is rotated around the pivot, which sits
 * {@code eyeHeight} above the feet face. Rotating it by {@code t} away from a plane whose face
 * coordinate is {@code p}, the lowest point of the box along that plane's normal ends up at
 * {@code dot(pivot, n) - eyeHeight*cos(t) - halfWidth*sin(t)}. Staying out of the plane is
 * therefore exactly:
 *
 * <pre>dot(pivot, n) &gt;= p + eyeHeight*cos(t) + halfWidth*sin(t)</pre>
 *
 * <p>Solving that as an equality for both planes at once gives one pivot position per angle: the
 * box rolls around the inside of the corner in permanent contact with both faces and never enters
 * either one. The same equations run backwards for wall to floor, which is why both directions
 * feel identical.
 */
final class PlaneTransition {
    private final Vector3d fromUp;
    private final Vector3d toUp;
    private final Vector3d tangent;
    private final double fromPlane;
    private final double toPlane;
    private final double eyeHeight;
    private final double lateral;
    private final int durationTicks;
    private final boolean drivesPosition;
    private int elapsedTicks;

    private PlaneTransition(final Vector3dc fromUp, final Vector3dc toUp, final Vector3dc tangent,
                            final double fromPlane, final double toPlane, final double eyeHeight,
                            final double lateral, final int durationTicks, final boolean drivesPosition) {
        this.fromUp = new Vector3d(fromUp);
        this.toUp = new Vector3d(toUp);
        this.tangent = new Vector3d(tangent);
        this.fromPlane = fromPlane;
        this.toPlane = toPlane;
        this.eyeHeight = eyeHeight;
        this.lateral = lateral;
        this.durationTicks = Math.max(1, durationTicks);
        this.drivesPosition = drivesPosition;
    }

    /** Orientation-only ease, used when first attaching so the view is never yanked. */
    static PlaneTransition alignment(final Vector3dc fromUp, final Vector3dc toUp, final int ticks) {
        return new PlaneTransition(fromUp, toUp, AdhesionMath.UP, 0.0, 0.0, 0.0, 0.0, ticks, false);
    }

    static @Nullable PlaneTransition corner(final Vector3dc fromUp, final Vector3dc toUp, final double fromPlane,
                                            final double toPlane, final double eyeHeight, final double halfWidth,
                                            final int ticks) {
        final Vector3d tangent = new Vector3d(fromUp).cross(toUp);
        if (tangent.lengthSquared() < 1.0e-8) return null;
        return new PlaneTransition(fromUp, toUp, tangent.normalize(), fromPlane, toPlane, eyeHeight,
                halfWidth + AdhesionSettings.CORNER_CLEARANCE, ticks, true);
    }

    boolean drivesPosition() {
        return this.drivesPosition;
    }

    void advance() {
        if (this.elapsedTicks < this.durationTicks) this.elapsedTicks++;
    }

    boolean finished() {
        return this.elapsedTicks >= this.durationTicks;
    }

    double progress() {
        return Math.min(1.0, this.elapsedTicks / (double) this.durationTicks);
    }

    double easedProgress() {
        return AdhesionMath.smoothStep(this.progress());
    }

    Vector3d upAt(final double eased, final Vector3d dest) {
        final double dot = Math.max(-1.0, Math.min(1.0, this.fromUp.dot(this.toUp)));
        if (dot > 0.999999) return dest.set(this.toUp);
        final Vector3d axis = new Vector3d(this.fromUp).cross(this.toUp);
        if (axis.lengthSquared() < 1.0e-12) return dest.set(this.toUp);
        axis.normalize();
        return dest.set(this.fromUp)
                .rotateAxis(Math.acos(dot) * eased, axis.x, axis.y, axis.z)
                .normalize();
    }

    /** Pivot position that keeps the rotated box tangent to both planes at this angle. */
    Vector3d pivotAt(final double eased, final Vector3dc currentPivotLocal, final Vector3d dest) {
        final double angle = eased * Math.PI * 0.5;
        final double cos = Math.cos(angle);
        final double sin = Math.sin(angle);
        final double fromDistance = this.eyeHeight * cos + this.lateral * sin;
        final double toDistance = this.eyeHeight * sin + this.lateral * cos;
        // The axis along the corner edge stays free, so the player keeps sliding along it.
        final double along = currentPivotLocal.dot(this.tangent);
        return dest.zero()
                .fma(this.fromPlane + fromDistance, this.fromUp)
                .fma(this.toPlane + toDistance, this.toUp)
                .fma(along, this.tangent);
    }
}
