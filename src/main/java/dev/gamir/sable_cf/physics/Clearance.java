package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * "Would the body fit if it leaned like this?"
 *
 * <h2>Why a lean has to be asked permission for</h2>
 *
 * <p>Sable rotates the collision box about the player's <b>eye</b>, not their feet:</p>
 *
 * <pre>offset = (0, eyeHeight - ysize/2, 0)
 *centre += offset - R * offset</pre>
 *
 * <p>So a lean of A degrees sweeps the feet sideways by {@code 2 * 1.62 * sin(A/2)} blocks - 0.28
 * at 10 degrees, 0.97 at 35, 2.3 at 90. Pivoting at the feet would swing the head <i>away</i> from
 * a wall you are leaning towards and cost nothing; pivoting at the eye drives the feet straight
 * into it.</p>
 *
 * <p>What happens next is not a gentle correction. Sable runs eight substeps, resolves up to four
 * penetrations in each, and its near-vertical branch redirects the entire minimum translation
 * vector along the body's up axis at full length:</p>
 *
 * <pre>if (dot &gt; 0.8) { entityUp.mul(maxMTV.dot(entityUp), maxMTV).normalize(preLength); }</pre>
 *
 * <p>A metre of penetration therefore comes back as a metre-long shove pointing up and out of the
 * wall. That is the whole mechanism behind "I leaned a little and it spat me through the wall", and
 * it is geometry rather than tuning - no combination of strengths avoids it.</p>
 *
 * <p>Since the pivot cannot be moved from outside Sable, the lean is asked in advance instead. The
 * body is only allowed to reach a posture it actually fits in, so the violent resolution never has
 * anything to resolve. Upstream ask #2 in {@code docs/UPSTREAM.md} is a feet pivot, and this file
 * deletes cleanly the day that lands.</p>
 *
 * <h2>The sample set</h2>
 *
 * <p>Eight corners plus the four vertical-edge midpoints. The midpoints matter more than they look:
 * a 1.8-block-tall box leaning towards a one-block-high lip clears it at every corner and passes
 * straight through it in the middle.</p>
 */
public final class Clearance {

    /**
     * Sample points in half-axis coordinates: eight corners, then four vertical-edge midpoints.
     */
    private static final double[][] SAMPLES = {
            {-1.0, -1.0, -1.0}, {1.0, -1.0, -1.0}, {-1.0, -1.0, 1.0}, {1.0, -1.0, 1.0},
            {-1.0, 1.0, -1.0}, {1.0, 1.0, -1.0}, {-1.0, 1.0, 1.0}, {1.0, 1.0, 1.0},
            {-1.0, 0.0, -1.0}, {1.0, 0.0, -1.0}, {-1.0, 0.0, 1.0}, {1.0, 0.0, 1.0},
    };

    /** Half-size of the tiny box each sample point is tested as, local blocks. */
    private static final double POINT_EPSILON = 0.015;

    /**
     * Whether the body would clear sub-level geometry at this orientation.
     *
     * <p>Errs towards {@code true}: an unloaded chunk, a degenerate pose or a non-finite number is
     * not a reason to freeze the body, only a reason not to claim knowledge.</p>
     *
     * @param orientation the candidate orientation, or null for upright
     */
    public static boolean fits(final Entity entity, final SubLevel subLevel,
                               @Nullable final Quaterniondc orientation) {

        if (orientation == null) {
            return true;
        }

        final Level level = subLevel.getLevel();

        if (level == null) {
            return true;
        }

        final double width = entity.getBbWidth();
        final double height = entity.getBbHeight();

        if (!(width > 0.0) || !(height > 0.0)) {
            return true;
        }

        final Pose3dc pose = subLevel.logicalPose();

        final double halfWidth = Math.max(0.02, width * 0.5 - CfConfig.CLEARANCE_SHRINK);
        final double halfHeight = Math.max(0.02, height * 0.5 - CfConfig.CLEARANCE_SHRINK);

        // Sable's pivot, reproduced exactly. Testing a box built any other way would be testing a
        // box Sable is not going to collide.
        final Vector3d offset = new Vector3d(0.0, entity.getEyeHeight() - height * 0.5, 0.0);
        final Vector3d rotatedOffset = orientation.transform(new Vector3d(offset));

        final Vector3d centre = new Vector3d(
                entity.getX(), entity.getY() + height * 0.5, entity.getZ())
                .add(offset)
                .sub(rotatedOffset);

        final Vector3d axisX = orientation.transform(new Vector3d(halfWidth, 0.0, 0.0));
        final Vector3d axisY = orientation.transform(new Vector3d(0.0, halfHeight, 0.0));
        final Vector3d axisZ = orientation.transform(new Vector3d(0.0, 0.0, halfWidth));

        if (!centre.isFinite() || !axisX.isFinite() || !axisY.isFinite() || !axisZ.isFinite()) {
            return true;
        }

        final Vector3d world = new Vector3d();
        final Vector3d local = new Vector3d();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (final double[] sample : SAMPLES) {
            world.set(centre)
                    .fma(sample[0], axisX)
                    .fma(sample[1], axisY)
                    .fma(sample[2], axisZ);

            pose.transformPositionInverse(world, local);

            if (!local.isFinite()) {
                continue;
            }

            cursor.set(
                    (int) Math.floor(local.x),
                    (int) Math.floor(local.y),
                    (int) Math.floor(local.z));

            // Never force a chunk load from a clearance test.
            if (!level.hasChunkAt(cursor)) {
                continue;
            }

            final BlockState state = level.getBlockState(cursor);

            if (state.isAir()) {
                continue;
            }

            final VoxelShape shape = state.getCollisionShape(level, cursor);

            if (shape.isEmpty()) {
                continue;
            }

            final AABB point = new AABB(
                    local.x - POINT_EPSILON, local.y - POINT_EPSILON, local.z - POINT_EPSILON,
                    local.x + POINT_EPSILON, local.y + POINT_EPSILON, local.z + POINT_EPSILON);

            if (Shapes.joinIsNotEmpty(
                    shape.move(cursor.getX(), cursor.getY(), cursor.getZ()),
                    Shapes.create(point),
                    BooleanOp.AND)) {
                return false;
            }
        }

        return true;
    }

    private Clearance() {
    }
}
