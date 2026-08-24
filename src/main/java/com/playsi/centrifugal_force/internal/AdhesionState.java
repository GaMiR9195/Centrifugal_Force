package com.playsi.centrifugal_force.internal;

import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Per-player adhesion state. One instance is created lazily the first time a player touches a
 * sub-level and reused afterwards.
 */
public final class AdhesionState {
    private boolean active;
    private @Nullable SubLevel subLevel;
    private boolean physicsPhase;

    private final Vector3d localUpPrevious = new Vector3d(AdhesionMath.UP);
    private final Vector3d localUpCurrent = new Vector3d(AdhesionMath.UP);

    private @Nullable PlaneTransition transition;
    private int supportMissTicks;
    private int reattachCooldownTicks;
    private double supportDistance = Double.NaN;

    public boolean isActive() {
        return this.active && this.subLevel != null && !this.subLevel.isRemoved();
    }

    public @Nullable SubLevel subLevel() {
        return this.isActive() ? this.subLevel : null;
    }

    public Vector3dc currentLocalUp() {
        return this.localUpCurrent;
    }

    public String planeLabel() {
        return AdhesionMath.axisLabel(this.localUpCurrent);
    }

    public boolean isChangingPlane() {
        return this.transition != null && this.transition.drivesPosition();
    }

    public double transitionProgress() {
        return this.transition == null ? 1.0 : this.transition.progress();
    }

    public double supportDistance() {
        return this.supportDistance;
    }

    public void setSupportDistance(final double distance) {
        this.supportDistance = distance;
    }

    public int supportMissTicks() {
        return this.supportMissTicks;
    }

    public void resetSupportMisses() {
        this.supportMissTicks = 0;
    }

    public int missSupport() {
        return ++this.supportMissTicks;
    }

    public void tickReattachCooldown() {
        if (!this.active && this.reattachCooldownTicks > 0) this.reattachCooldownTicks--;
    }

    public boolean canAttach() {
        return !this.active && this.reattachCooldownTicks == 0;
    }

    /**
     * Freezes the local plane for the whole physics tick.
     *
     * <p>Sable derives the motion a sub-level hands to its riders from how far the feet anchor
     * moved inside a single collide() pass, then applies that motion with a main-level-only
     * collide. A local plane rotation sampled per substep therefore shows up as a large inherited
     * velocity that is not checked against sub-level blocks at all: the player is pushed straight
     * through the wall in the direction the feet swung, and the value is fed back as velocity so
     * it keeps accelerating. Holding the local part still for the tick makes that term exactly
     * zero, while the frame part stays live so real deck rotation is still inherited.
     */
    public void beginPhysicsTick() {
        this.localUpPrevious.set(this.localUpCurrent);
        this.physicsPhase = true;
    }

    public void endPhysicsTick() {
        this.physicsPhase = false;
    }

    public void attach(final SubLevel newSubLevel, final Vector3dc supportLocalUp, final Vector3dc localWorldUp) {
        this.subLevel = newSubLevel;
        this.active = true;
        this.supportMissTicks = 0;
        this.reattachCooldownTicks = 0;
        this.supportDistance = Double.NaN;

        // Start from the local direction that already maps onto world up, so the very first
        // orientation is identity and attaching cannot move the view at all.
        this.localUpCurrent.set(localWorldUp).normalize();
        this.localUpPrevious.set(this.localUpCurrent);

        final Vector3d target = new Vector3d(supportLocalUp).normalize();
        if (this.localUpCurrent.dot(target) > 0.9995) {
            this.localUpCurrent.set(target);
            this.transition = null;
        } else {
            this.transition = PlaneTransition.alignment(this.localUpCurrent, target,
                    AdhesionSettings.ATTACH_ALIGN_TICKS);
        }
    }

    public void detach() {
        this.active = false;
        this.subLevel = null;
        this.transition = null;
        this.supportMissTicks = 0;
        this.supportDistance = Double.NaN;
        this.reattachCooldownTicks = AdhesionSettings.REATTACH_COOLDOWN_TICKS;
        this.localUpPrevious.set(AdhesionMath.UP);
        this.localUpCurrent.set(AdhesionMath.UP);
    }

    public boolean beginPlaneChange(final Vector3dc targetLocalUp, final double fromPlane, final double toPlane,
                                   final double eyeHeight, final double halfWidth) {
        if (!this.isActive() || this.transition != null) return false;
        final PlaneTransition next = PlaneTransition.corner(this.localUpCurrent, targetLocalUp, fromPlane, toPlane,
                eyeHeight, halfWidth, AdhesionSettings.CORNER_TICKS);
        if (next == null) return false;
        this.transition = next;
        return true;
    }

    public void advanceTransition() {
        if (this.transition == null) return;
        this.transition.advance();
        this.transition.upAt(this.transition.easedProgress(), this.localUpCurrent);
    }

    public @Nullable Vector3d transitionPivotTarget(final Vector3dc currentPivotLocal, final Vector3d dest) {
        if (this.transition == null || !this.transition.drivesPosition()) return null;
        return this.transition.pivotAt(this.transition.easedProgress(), currentPivotLocal, dest);
    }

    public boolean finishTransitionIfComplete() {
        if (this.transition == null || !this.transition.finished()) return false;
        this.transition.upAt(1.0, this.localUpCurrent);
        AdhesionMath.snapAxis(this.localUpCurrent, this.localUpCurrent);
        this.transition = null;
        return true;
    }

    public @Nullable Quaterniondc orientationAt(final float partialTick) {
        if (!this.isActive()) return null;
        final SubLevel sub = this.subLevel;
        if (sub == null) return null;
        final double t = Math.max(0.0, Math.min(1.0, partialTick));

        final Vector3d localUp = new Vector3d();
        if (this.physicsPhase || !sub.getLevel().isClientSide()) {
            localUp.set(this.localUpCurrent);
        } else {
            localUp.set(this.localUpPrevious).lerp(this.localUpCurrent, t);
            if (localUp.lengthSquared() < 1.0e-9) localUp.set(this.localUpCurrent);
            else localUp.normalize();
        }

        // The frame is read straight from the sub-level's own poses every single time and never
        // integrated or smoothed, so the player cannot fall behind the sub-level's rotation.
        final Quaterniond frame = new Quaterniond(sub.lastPose().orientation())
                .slerp(sub.logicalPose().orientation(), t);
        final Vector3d worldUp = frame.transform(localUp, new Vector3d()).normalize();
        final Vector3d fallbackAxis = frame.transform(new Vector3d(1.0, 0.0, 0.0));
        return AdhesionMath.swingFromUp(worldUp, fallbackAxis, new Quaterniond());
    }

    public double tiltDegrees() {
        final Quaterniondc orientation = this.orientationAt(1.0f);
        if (orientation == null) return 0.0;
        return Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs(orientation.w()))));
    }
}
