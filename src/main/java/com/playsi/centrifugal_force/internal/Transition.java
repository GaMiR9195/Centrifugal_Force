package com.playsi.centrifugal_force.internal;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** A timed change of the box orientation, plus the planes the box must stay tangent to. */
final class Transition {
    static final Contact[] NONE = {};

    /** A plane the box rests on: its outward normal and its coordinate along that normal. */
    record Contact(Vector3dc normal, double plane) {}

    private final Quaterniond from = new Quaterniond();
    private final Quaterniond to = new Quaterniond();
    private final Contact[] contacts;
    private final @Nullable Contact result;
    private final int ticks;
    private int elapsed;

    Transition(final Quaterniondc from, final Quaterniondc to, final int ticks, final Contact[] contacts,
               final @Nullable Contact result) {
        this.from.set(from);
        this.to.set(to);
        this.ticks = ticks;
        this.contacts = contacts;
        this.result = result;
    }

    /**
     * Ninety degrees about the edge shared by two perpendicular planes. Holding both contacts for
     * the whole rotation is what keeps the box tangent to the plane it leaves and the one it enters
     * at the same time, so it rolls over the corner instead of clipping into either.
     */
    static Transition roll(final Quaterniondc from, final Vector3dc fromUp, final double fromPlane,
                           final Vector3dc toUp, final double toPlane, final int ticks) {
        final Contact leaving = new Contact(new Vector3d(fromUp), fromPlane);
        final Contact entering = new Contact(new Vector3d(toUp), toPlane);
        final Vector3d axis = new Vector3d(fromUp).cross(toUp);
        return new Transition(from, Rotations.rotateAbout(axis, Math.PI * 0.5, from, new Quaterniond()),
                ticks, new Contact[]{leaving, entering}, entering);
    }

    Contact[] contacts() {
        return this.contacts;
    }

    /** Support plane once this transition completes, or null when adhesion ends with it. */
    @Nullable Contact result() {
        return this.result;
    }

    void advance() {
        this.elapsed++;
    }

    boolean finished() {
        return this.elapsed >= this.ticks;
    }

    double progress() {
        return (double) this.elapsed / this.ticks;
    }

    Quaterniond orientationAt(final double progress, final Quaterniond dest) {
        return this.from.slerp(this.to, Rotations.ease(progress), dest);
    }
}
