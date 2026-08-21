package io.github.minerguy341.surefooting;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Rotates a velocity with a sub-level's per-tick orientation delta, with an angle multiplier to
 * compensate phase lag (the velocity aligned at a tick's end is applied during the NEXT tick while
 * the frame keeps rotating). Callers keep a per-subject anchor of the last observed orientation.
 */
final class FrameRotation {

    /**
     * Mutable per-subject anchor: which sub-level's orientation was last sampled, and its value.
     */
    static final class Anchor {
        private SubLevel subLevel;
        private final Quaterniond lastOrientation = new Quaterniond();

        void reset() {
            this.subLevel = null;
        }
    }

    /**
     * Samples the sub-level's orientation and, if the anchor already followed this sub-level,
     * rotates the entity's delta movement by the per-tick delta scaled to {@code strength}.
     * When {@code preserveY} is set (grounded subjects) the vertical component is left untouched
     * so tilted sub-levels don't fight gravity and ground snapping.
     */
    static void rotateWithFrame(final Anchor anchor, final Entity entity, final SubLevel subLevel,
                                final double strength, final boolean preserveY) {
        rotateWithFrame(anchor, entity, subLevel, strength, preserveY, false);
    }

    static void rotateWithFrame(final Anchor anchor, final Entity entity, final SubLevel subLevel,
                                final double strength, final boolean preserveY, final boolean rotateYaw) {
        final Quaterniondc orientation = subLevel.logicalPose().orientation();

        // A (near-)identity delta means the frame didn't rotate this tick: there is nothing to
        // rotate, and converting an identity quaternion to axis-angle for the strength scaling
        // divides by zero, poisoning the velocity with NaN. (This is how simply standing on a
        // STATIC contraption froze the player and crashed the client.) |w| within ~1e-10 of 1
        // corresponds to a rotation below ~2e-5 rad/tick — far below any visible motion.
        if (anchor.subLevel == subLevel) {
            final Quaterniond delta = orientation.mul(new Quaterniond(anchor.lastOrientation).invert(), new Quaterniond()).normalize();

            if (Math.abs(delta.w) < 1.0 - 1.0e-10) {
                if (rotateYaw) {
                    rotateEntityYaw(entity, delta);
                }

                if (strength != 0.0) {
                    Quaterniond scaled = delta;
                    if (strength != 1.0) {
                        final AxisAngle4d axisAngle = new AxisAngle4d().set(delta);
                        axisAngle.angle *= strength;
                        scaled = new Quaterniond(axisAngle);
                    }

                    final Vec3 movement = entity.getDeltaMovement();
                    // When the vertical component is going to be preserved anyway, leave it out of
                    // the rotation entirely rather than rotating it and discarding the result: on a
                    // frame turning about a HORIZONTAL axis, a rotated y bleeds into x/z, so a
                    // player standing still on a pitching deck (dm ~ (0, -0.0784, 0) between ground
                    // snaps) gets pushed sideways by gravity's own velocity.
                    final Vector3d rotated = scaled.transform(
                            new Vector3d(movement.x, preserveY ? 0.0 : movement.y, movement.z));

                    // Never let a degenerate transform write non-finite velocity onto the entity.
                    if (rotated.isFinite()) {
                        entity.setDeltaMovement(rotated.x, preserveY ? movement.y : rotated.y, rotated.z);
                    }
                }
            }
        }

        anchor.subLevel = subLevel;
        anchor.lastOrientation.set(orientation);
    }

    /**
     * Turns the entity with the frame's per-tick yaw so it keeps facing the same direction
     * relative to the sub-level (a mob on a spinning platform otherwise keeps its world-frame
     * heading while the platform turns under it). Uses the raw delta — orientation has no phase
     * lag to compensate.
     */
    private static void rotateEntityYaw(final Entity entity, final Quaterniondc delta) {
        // Yaw component of the delta quaternion; exact for Y-axis rotations, a good approximation
        // for mostly-upright frames. Minecraft yaw is clockwise-positive, opposite the JOML angle.
        final float yawDegrees = (float) -Math.toDegrees(2.0 * Math.atan2(delta.y(), delta.w()));

        if (Math.abs(yawDegrees) < 1.0e-5f) {
            return;
        }

        // Note: deliberately NOT shifting the previous-tick rotations (yRotO etc.) — doing so
        // amplifies the wobble; the client interpolates rotation in world frame on its own
        // timeline, and a residual sub-degree jitter from that is currently unavoidable server-side.
        entity.setYRot(entity.getYRot() + yawDegrees);

        if (entity instanceof final LivingEntity living) {
            living.yBodyRot += yawDegrees;
            living.yHeadRot += yawDegrees;
        }
    }

    private FrameRotation() {
    }
}
