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
 * <h2>What the pivot actually does, and why it had to be fixed rather than worked around</h2>
 *
 * <p>Sable rotates the collision box about the player's <b>eye</b>:</p>
 *
 * <pre>offset = (0, eyeHeight - ysize/2, 0)      // 0.72 for a standing player
 *centre += offset - R * offset</pre>
 *
 * <p>The box centre therefore sweeps {@code 2 * 0.72 * sin(A/2)} blocks for a lean of A, and the
 * <i>feet</i> - the corner the player cares about - sweep further still, because the feet sit
 * another 0.9 below the centre. Rotating a body about a point 1.62 up is not a lean, it is a
 * cartwheel: the feet leave the surface they are standing on and go through the wall behind
 * them.</p>
 *
 * <p>What happens next is not a gentle correction. Sable runs eight substeps, resolves up to four
 * penetrations in each, and its near-vertical branch redirects the entire minimum translation
 * vector along the body's up axis at full length:</p>
 *
 * <pre>if (dot &gt; 0.8) { entityUp.mul(maxMTV.dot(entityUp), maxMTV).normalize(preLength); }</pre>
 *
 * <p>A metre of penetration comes back as a metre-long shove pointing up and out of the wall. That
 * is the reported "it starts lifting me up and shaking", and it is geometry, not tuning - no
 * combination of strengths avoids it, because the penetration is created before any force is
 * consulted.</p>
 *
 * <p>So the pivot is no longer worked around: {@code SubLevelEntityCollisionMixin} cancels it, and
 * the box rotates about its own centre. A centred rotation of a 0.6 x 1.8 x 0.6 box sweeps at most
 * {@code (1.8 - 0.6) / 2 = 0.6} of a block at 90 degrees, and sweeps <b>nothing at all</b> over a
 * full turn - after 360 degrees the box is exactly where it started, which is what makes the
 * "solnyshko" loop safe to follow all the way round.</p>
 *
 * <h2>Why this class still exists</h2>
 *
 * <p>0.6 of a block is small but it is not zero, and the swept volume is still real: leaning
 * towards a wall you are already brushing can put a shoulder inside it. This asks first, and the
 * body is only allowed to reach a posture it fits in, so the violent resolution above never has
 * anything to resolve. It tests the same box Sable will - including the pivot Sable will actually
 * use, which is why it reads the same config flag the mixin does.</p>
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

        final Vector3d centre = new Vector3d(
                entity.getX(), entity.getY() + height * 0.5, entity.getZ());

        if (!CfConfig.HITBOX_CENTRE_PIVOT.get()) {
            // The mixin is off, or did not apply. Reproduce Sable's eye pivot exactly - testing a
            // box built any other way would be testing a box Sable is not going to collide.
            final Vector3d offset = new Vector3d(0.0, entity.getEyeHeight() - height * 0.5, 0.0);
            final Vector3d rotatedOffset = orientation.transform(new Vector3d(offset));

            centre.add(offset).sub(rotatedOffset);
        }

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
