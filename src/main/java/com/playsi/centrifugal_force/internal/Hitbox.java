package com.playsi.centrifugal_force.internal;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The oriented hitbox, in sub-level space.
 *
 * <p>Sable builds the rotated box by turning the vanilla box around the eye, so a tilted pose can
 * only be described through the box centre: {@code centre = position + eye - R * (eye - height/2)}.
 * Driving that centre and inverting the relation at the very end is what keeps the feet on the
 * surface instead of swinging them through the geometry the body rotates towards.
 */
final class Hitbox {
    private final Vector3d halfExtents = new Vector3d();
    private final Vector3d eye = new Vector3d();
    private final Vector3d pivot = new Vector3d();
    private final Vector3d[] axes = {new Vector3d(), new Vector3d(), new Vector3d()};
    private final Vector3d scratch = new Vector3d();

    Hitbox(final Player player) {
        final AABB box = player.getBoundingBox();
        final double halfHeight = box.getYsize() * 0.5;
        this.halfExtents.set(box.getXsize() * 0.5, halfHeight, box.getZsize() * 0.5);
        this.eye.set(0.0, player.getEyeHeight(), 0.0);
        this.pivot.set(0.0, player.getEyeHeight() - halfHeight, 0.0);
    }

    /** @param localOrientation orientation of the box inside the sub-level */
    Hitbox orient(final Quaterniondc localOrientation) {
        localOrientation.transform(this.axes[0].set(1.0, 0.0, 0.0));
        localOrientation.transform(this.axes[1].set(0.0, 1.0, 0.0));
        localOrientation.transform(this.axes[2].set(0.0, 0.0, 1.0));
        return this;
    }

    /** Half width of the box measured along {@code direction}. */
    double extent(final Vector3dc direction) {
        return this.halfExtents.x * Math.abs(this.axes[0].dot(direction))
                + this.halfExtents.y * Math.abs(this.axes[1].dot(direction))
                + this.halfExtents.z * Math.abs(this.axes[2].dot(direction));
    }

    Vector3d localCentre(final Vec3 position, final Quaterniondc orientation, final Pose3dc pose,
                         final Vector3d dest) {
        orientation.transform(this.scratch.set(this.pivot));
        dest.set(position.x, position.y, position.z).add(this.eye).sub(this.scratch);
        return pose.transformPositionInverse(dest, dest);
    }

    Vec3 position(final Vector3dc localCentre, final Quaterniondc orientation, final Pose3dc pose) {
        final Vector3d world = pose.transformPosition(localCentre, new Vector3d());
        orientation.transform(this.scratch.set(this.pivot));
        world.sub(this.eye).add(this.scratch);
        return new Vec3(world.x, world.y, world.z);
    }

    /** Feet of the rotated box: the anchor a sub-level has to carry. */
    Vec3 feet(final Vec3 position, final Quaterniondc orientation) {
        orientation.transform(this.scratch.set(this.eye));
        return new Vec3(position.x + this.eye.x - this.scratch.x,
                position.y + this.eye.y - this.scratch.y,
                position.z + this.eye.z - this.scratch.z);
    }

    /** Moves {@code localCentre} along {@code normal} until the box rests exactly on that plane. */
    void rest(final Vector3d localCentre, final Vector3dc normal, final double plane) {
        localCentre.fma(plane + this.extent(normal) - localCentre.dot(normal), normal);
    }

    /** Distance between the box face and the plane, negative while penetrating. */
    double gap(final Vector3dc localCentre, final Vector3dc normal, final double plane) {
        return localCentre.dot(normal) - plane - this.extent(normal);
    }

    void corners(final Vector3dc localCentre, final double skin, final Vector3d[] dest) {
        for (int corner = 0; corner < dest.length; corner++) {
            dest[corner].set(localCentre);
            for (int axis = 0; axis < 3; axis++) {
                final double half = Math.max(0.0, this.halfExtents.get(axis) - skin);
                dest[corner].fma((corner >> axis & 1) == 0 ? -half : half, this.axes[axis]);
            }
        }
    }
}
