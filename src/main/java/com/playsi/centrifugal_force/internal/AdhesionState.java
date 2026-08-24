package com.playsi.centrifugal_force.internal;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Adhesion state of a single entity. */
public final class AdhesionState {
    /** Orientation of the box inside the sub-level. */
    private final Quaterniond local = new Quaterniond();
    /** World orientation the running physics tick was set up with. */
    private final Quaterniond current = new Quaterniond();
    /** World orientation of the previous tick, for render and camera interpolation. */
    private final Quaterniond previous = new Quaterniond();
    private final Vector3d support = new Vector3d();
    private @Nullable SubLevel subLevel;
    private @Nullable Transition transition;
    private @Nullable Vec3 frameCarry;
    private double supportPlane;
    private boolean active;
    private boolean grounded;
    private boolean physicsTick;

    /**
     * The orientation Sable applies to the hitbox, to input and to the camera. It is pinned for the
     * whole physics tick: an orientation that moves between collision substeps rotates the box
     * around the eye mid-move, and Sable reads that displacement back as inherited velocity.
     */
    public @Nullable Quaterniondc orientation(final float partialTicks) {
        if (!this.active) return null;
        if (this.physicsTick) return this.current;
        return this.previous.slerp(this.current, Math.max(0.0f, Math.min(1.0f, partialTicks)), new Quaterniond());
    }

    public boolean isActive() {
        return this.active;
    }

    public @Nullable Vec3 frameCarry() {
        return this.frameCarry;
    }

    public String planeLabel() {
        return Rotations.label(this.support);
    }

    public double tiltDegrees() {
        final Vector3d up = this.current.transform(new Vector3d(Rotations.UP));
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.y))));
    }

    public boolean isChangingPlane() {
        return this.transition != null;
    }

    public double transitionProgress() {
        return this.transition == null ? 1.0 : this.transition.progress();
    }

    public boolean isGrounded() {
        return this.grounded;
    }

    @Nullable SubLevel subLevel() {
        return this.subLevel;
    }

    Quaterniondc localOrientation() {
        return this.local;
    }

    Quaterniondc currentOrientation() {
        return this.current;
    }

    Vector3dc support() {
        return this.support;
    }

    double supportPlane() {
        return this.supportPlane;
    }

    void setSupportPlane(final double plane) {
        this.supportPlane = plane;
    }

    void setGrounded(final boolean grounded) {
        this.grounded = grounded;
    }

    void setFrameCarry(final @Nullable Vec3 carry) {
        this.frameCarry = carry;
    }

    boolean isChanging() {
        return this.transition != null;
    }

    void attach(final SubLevel subLevel, final Vector3dc support, final double plane, final Quaterniondc local) {
        this.subLevel = subLevel;
        this.support.set(support);
        this.supportPlane = plane;
        this.local.set(local);
        this.current.identity();
        this.previous.identity();
        this.transition = null;
        this.frameCarry = null;
        this.active = true;
    }

    void begin(final Transition transition) {
        this.transition = transition;
    }

    Transition.Contact[] contacts(final boolean grounded) {
        if (this.transition != null) return this.transition.contacts();
        if (!grounded) return Transition.NONE;
        return new Transition.Contact[]{new Transition.Contact(new Vector3d(this.support), this.supportPlane)};
    }

    /** @return true when the transition that just finished ends adhesion */
    boolean advance() {
        final Transition transition = this.transition;
        if (transition == null) return false;

        transition.advance();
        transition.orientationAt(transition.progress(), this.local);
        if (!transition.finished()) return false;

        this.transition = null;
        final Transition.Contact result = transition.result();
        if (result == null) return true;
        this.support.set(result.normal());
        this.supportPlane = result.plane();
        return false;
    }

    void commit(final Quaterniondc orientation) {
        this.previous.set(this.current);
        this.current.set(orientation);
    }

    void clear() {
        this.active = false;
        this.grounded = false;
        this.subLevel = null;
        this.transition = null;
        this.frameCarry = null;
    }

    void beginPhysicsTick() {
        this.physicsTick = true;
    }

    void endPhysicsTick() {
        this.physicsTick = false;
    }
}
