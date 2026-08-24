package com.playsi.centrifugal_force.internal;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Ray queries against a sub-level's blocks. Sub-level blocks live at local coordinates, so every
 * ray is cast in local space with projection disabled.
 */
final class SurfaceProbe {
    private SurfaceProbe() {}

    /**
     * @param distance perpendicular distance from the ray origin to the face, along {@code axis}
     * @param planeCoordinate the face's own coordinate along {@code axis}
     */
    record Hit(double distance, double planeCoordinate) {}

    /** Casts from {@code fromLocal} along {@code -axis} and keeps only faces that push back along {@code axis}. */
    static @Nullable Hit probe(final Player player, final SubLevel subLevel, final Vector3dc fromLocal,
                              final Vector3dc axis, final double reach) {
        final Vector3d to = new Vector3d(fromLocal).fma(-reach, axis);
        final BlockHitResult hit = clip(player, subLevel, fromLocal, to);
        if (hit.getType() == HitResult.Type.MISS) return null;

        final Vector3d normal = new Vector3d(
                hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ());
        if (normal.dot(axis) < 0.9) return null;

        final Vec3 at = hit.getLocation();
        final double planeCoordinate = new Vector3d(at.x, at.y, at.z).dot(axis);
        return new Hit(fromLocal.dot(axis) - planeCoordinate, planeCoordinate);
    }

    /** Distance from the player's pivot to the surface under {@code localUp}, or NaN. */
    static double distanceToSurface(final Player player, final SubLevel subLevel, final Vector3dc localUp,
                                    final double extraReach) {
        final Pose3dc pose = subLevel.logicalPose();
        final Vector3d eyeLocal = localPivot(player, pose, new Vector3d());
        final Hit hit = probe(player, subLevel, eyeLocal, localUp, player.getEyeHeight() + extraReach);
        return hit == null ? Double.NaN : hit.distance();
    }

    static boolean isClear(final Player player, final SubLevel subLevel, final Vector3dc fromLocal,
                           final Vector3dc toLocal) {
        return clip(player, subLevel, fromLocal, toLocal).getType() == HitResult.Type.MISS;
    }

    static boolean isClear(final Player player, final SubLevel subLevel, final Vector3dc fromLocal,
                           final Vector3dc direction, final double reach) {
        return isClear(player, subLevel, fromLocal, new Vector3d(fromLocal).fma(reach, direction));
    }

    /**
     * Sable rotates the hitbox around the vanilla eye position, so that point, not the feet, is
     * the pivot this mod has to reason about and place.
     */
    static Vector3d localPivot(final Player player, final Pose3dc pose, final Vector3d dest) {
        final Vec3 position = player.position();
        dest.set(position.x, position.y + player.getEyeHeight(), position.z);
        return pose.transformPositionInverse(dest, dest);
    }

    private static BlockHitResult clip(final Player player, final SubLevel subLevel, final Vector3dc fromLocal,
                                       final Vector3dc toLocal) {
        final ClipContext context = new ClipContext(
                new Vec3(fromLocal.x(), fromLocal.y(), fromLocal.z()),
                new Vec3(toLocal.x(), toLocal.y(), toLocal.z()),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        ((ClipContextExtension) context).sable$setDoNotProject(true);
        return subLevel.getLevel().clip(context);
    }
}
