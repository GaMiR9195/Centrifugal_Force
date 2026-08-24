package com.playsi.centrifugal_force.internal;

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

/** Ray queries against a sub-level. Its blocks live in local space, so the rays are local too. */
final class SurfaceProbe {
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7},
    };

    private SurfaceProbe() {}

    /** A block face: its outward normal and its own coordinate along that normal. */
    record Face(Vector3dc normal, double plane) {}

    static @Nullable Face cast(final Player player, final SubLevel subLevel, final Vector3dc from,
                               final Vector3dc direction, final double reach) {
        final BlockHitResult hit = clip(player, subLevel, from, new Vector3d(from).fma(reach, direction));
        if (hit.getType() == HitResult.Type.MISS) return null;

        final Vector3d normal = new Vector3d(hit.getDirection().getStepX(), hit.getDirection().getStepY(),
                hit.getDirection().getStepZ());
        final Vec3 at = hit.getLocation();
        return new Face(normal, normal.x * at.x + normal.y * at.y + normal.z * at.z);
    }

    /** True when no block intersects the box described by these corners. */
    static boolean clear(final Player player, final SubLevel subLevel, final Vector3d[] corners) {
        for (final int[] edge : EDGES) {
            if (clip(player, subLevel, corners[edge[0]], corners[edge[1]]).getType() != HitResult.Type.MISS) {
                return false;
            }
        }
        return true;
    }

    private static BlockHitResult clip(final Player player, final SubLevel subLevel, final Vector3dc from,
                                       final Vector3dc to) {
        final ClipContext context = new ClipContext(new Vec3(from.x(), from.y(), from.z()),
                new Vec3(to.x(), to.y(), to.z()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        ((ClipContextExtension) context).sable$setDoNotProject(true);
        return subLevel.getLevel().clip(context);
    }
}
