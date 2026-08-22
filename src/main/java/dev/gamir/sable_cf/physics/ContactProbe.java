package dev.gamir.sable_cf.physics;

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
import org.joml.Vector3dc;

/**
 * What the player is actually touching, on all six sides.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The original approach picked the surface normal by asking which of the sub-level's six axes
 * best opposed felt-down. That is a guess about geometry made from a force, and it has one fatal
 * property: it always answers. Point felt-down sideways and it confidently reports a wall, whether
 * or not a wall is there - so a player standing on an ordinary deck could be tilted and shoved by a
 * surface that did not exist.</p>
 *
 * <p>This asks the geometry instead. It is also what makes detection <i>global</i> rather than
 * floor-only: walls and ceilings are found the same way the floor is, so being pinned to the inside
 * of a drum, wedged in a corner, or hanging off the top of a loop are first-class states rather
 * than side effects.</p>
 *
 * <h2>The probe follows the body</h2>
 *
 * <p>The box is built from the body's <i>oriented</i> extents, not from an upright box along local
 * +Y. That was a real failure and not a refinement: once the body lay against a drum wall, an
 * upright probe box went looking for a floor a metre below where the body actually was, found
 * nothing, and reported no contacts at all - which zeroed the press, which dropped the tilt, which
 * stood the body back up. Wall riding could not survive its own first tick.</p>
 *
 * <p>Enclosing an oriented box in an axis-aligned one does widen it, and that is fine <i>here</i>:
 * this decides where to look for geometry. It is not the hitbox. What Sable is handed is an
 * orientation, and Sable builds a genuine oriented box from it - nothing this class does reaches
 * collision.</p>
 *
 * <h2>How it can query sub-level blocks at all</h2>
 *
 * <p>A sub-level's blocks live in the parent {@link Level}, in a plot far out from the origin -
 * Sable's own collision code walks them with plain {@code BlockPos} lookups in the sub-level's
 * local coordinates. So do we. The pose converts between the two spaces, and no internal API is
 * touched.</p>
 *
 * <p>This is also why a raycast is the wrong tool: hits come back in local space millions of blocks
 * away, and a ray answers about one point rather than about a face.</p>
 */
public final class ContactProbe {

    /** Local axis directions, in the order results are indexed by. Each is an outward face normal. */
    private static final int[][] AXES = {
            {0, 1, 0}, {0, -1, 0},
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
    };

    public static final int AXIS_COUNT = 6;

    /**
     * How far outside the body to look, in local blocks.
     *
     * <p>Wide enough to survive Sable resolving contact to within a fraction of a block and to
     * survive one tick of drift on a moving wall; narrow enough that a floor one block below is not
     * mistaken for a floor you are standing on.</p>
     */
    private static final double PROBE_DEPTH = 0.12;

    /**
     * How much to pull the slab in on its other two axes.
     *
     * <p>Without it, standing on a floor also reports both walls of the block you are inside,
     * because the corner of the floor slab touches them. Shrinking the slab means a face has to be
     * genuinely in front of you, not diagonally adjacent.</p>
     */
    private static final double SIDE_SHRINK = 0.08;

    private final boolean[] contact = new boolean[AXIS_COUNT];
    private final Vector3d[] normals = new Vector3d[AXIS_COUNT];

    private int count;

    public ContactProbe() {
        for (int i = 0; i < AXIS_COUNT; i++) {
            this.normals[i] = new Vector3d(0.0, 1.0, 0.0);
        }
    }

    /** True if the face whose outward normal is local axis {@code index} is in contact. */
    public boolean contact(final int index) {
        return this.contact[index];
    }

    /** World-space unit outward normal of face {@code index}. Only meaningful when in contact. */
    public Vector3dc normal(final int index) {
        return this.normals[index];
    }

    /** How many of the six faces are in contact. Zero means airborne, and nothing should happen. */
    public int count() {
        return this.count;
    }

    public boolean any() {
        return this.count > 0;
    }

    public void clear() {
        this.count = 0;

        for (int i = 0; i < AXIS_COUNT; i++) {
            this.contact[i] = false;
        }
    }

    /**
     * Refills the contact set for this entity against this sub-level.
     *
     * @param bodyOrientation the body's current orientation, or null for upright. One tick stale by
     *                        construction - the tilt is derived from the contacts - which is
     *                        harmless at a 0.16 s orientation half-life and is what breaks the
     *                        circular dependency.
     * @return the number of contacting faces
     */
    public int probe(final Entity entity, final SubLevel subLevel,
                     @Nullable final Quaterniondc bodyOrientation) {

        this.clear();

        final Level level = subLevel.getLevel();

        if (level == null) {
            return 0;
        }

        final Pose3dc pose = subLevel.logicalPose();
        final Vector3dc scale = pose.scale();

        if (!(Math.abs(scale.x()) > 1.0e-6)
                || !(Math.abs(scale.y()) > 1.0e-6)
                || !(Math.abs(scale.z()) > 1.0e-6)) {
            return 0;
        }

        final double width = entity.getBbWidth();
        final double height = entity.getBbHeight();

        if (!(width > 0.0) || !(height > 0.0)) {
            return 0;
        }

        final Vector3d feet = pose.transformPositionInverse(
                new Vector3d(entity.getX(), entity.getY(), entity.getZ()), new Vector3d());

        if (!feet.isFinite()) {
            return 0;
        }

        // The body's three half-axis vectors, in local space. transformNormalInverse carries the
        // inverse scale, so these are already in local units and no division is needed.
        final Vector3d halfX = localHalfAxis(pose, bodyOrientation, width * 0.5, 0.0, 0.0);
        final Vector3d halfY = localHalfAxis(pose, bodyOrientation, 0.0, height * 0.5, 0.0);
        final Vector3d halfZ = localHalfAxis(pose, bodyOrientation, 0.0, 0.0, width * 0.5);

        if (halfX == null || halfY == null || halfZ == null) {
            return 0;
        }

        // Half the body's height along its OWN up, which is where its centre is. Upright this is
        // the familiar feet + height/2; lying against a wall it is off to the side, which is the
        // entire point.
        final Vector3d centre = new Vector3d(feet).add(halfY);

        // Enclosing half-extents of the oriented body along each local axis - the standard
        // OBB-to-AABB projection. Exact when upright and when fully on a wall; only loose at the
        // 45 degree midpoint, where a slightly generous search area is the safe direction to err.
        final double extentX = Math.abs(halfX.x) + Math.abs(halfY.x) + Math.abs(halfZ.x);
        final double extentY = Math.abs(halfX.y) + Math.abs(halfY.y) + Math.abs(halfZ.y);
        final double extentZ = Math.abs(halfX.z) + Math.abs(halfY.z) + Math.abs(halfZ.z);

        if (!(extentX > 0.0) || !(extentY > 0.0) || !(extentZ > 0.0)) {
            return 0;
        }

        final AABB body = new AABB(
                centre.x - extentX, centre.y - extentY, centre.z - extentZ,
                centre.x + extentX, centre.y + extentY, centre.z + extentZ);

        for (int i = 0; i < AXIS_COUNT; i++) {
            final int[] axis = AXES[i];

            final AABB slab = shell(body, axis);

            if (slab == null || !occupied(level, slab)) {
                continue;
            }

            final Vector3d worldNormal = pose.transformNormal(
                    new Vector3d(axis[0], axis[1], axis[2]), new Vector3d());

            final double length = worldNormal.length();

            // transformNormal carries the sub-level's scale, so it has to be normalised before it
            // can be compared with anything or dotted against a unit vector.
            if (length < 1.0e-9 || !worldNormal.isFinite()) {
                continue;
            }

            this.normals[i].set(worldNormal.div(length));
            this.contact[i] = true;
            this.count++;
        }

        return this.count;
    }

    /** One half-axis of the body, rotated into the body's orientation and then into local space. */
    @Nullable
    private static Vector3d localHalfAxis(final Pose3dc pose,
                                          @Nullable final Quaterniondc orientation,
                                          final double x, final double y, final double z) {

        final Vector3d axis = new Vector3d(x, y, z);

        if (orientation != null) {
            orientation.transform(axis);
        }

        final Vector3d local = new Vector3d();

        pose.transformNormalInverse(axis, local);

        return local.isFinite() ? local : null;
    }

    /**
     * The thin slab just outside the face opposite {@code axis}.
     *
     * <p>For {@code axis = +Y} - the floor's outward normal - that is the sliver directly under the
     * feet. Reading it the other way round is the classic sign error here, so: the face whose
     * normal points at you is the face you are resting on.</p>
     */
    @Nullable
    private static AABB shell(final AABB body, final int[] axis) {
        double minX = body.minX + SIDE_SHRINK;
        double maxX = body.maxX - SIDE_SHRINK;
        double minY = body.minY + SIDE_SHRINK;
        double maxY = body.maxY - SIDE_SHRINK;
        double minZ = body.minZ + SIDE_SHRINK;
        double maxZ = body.maxZ - SIDE_SHRINK;

        if (axis[0] != 0) {
            if (axis[0] > 0) {
                maxX = body.minX;
                minX = body.minX - PROBE_DEPTH;
            } else {
                minX = body.maxX;
                maxX = body.maxX + PROBE_DEPTH;
            }
        } else if (axis[1] != 0) {
            if (axis[1] > 0) {
                maxY = body.minY;
                minY = body.minY - PROBE_DEPTH;
            } else {
                minY = body.maxY;
                maxY = body.maxY + PROBE_DEPTH;
            }
        } else {
            if (axis[2] > 0) {
                maxZ = body.minZ;
                minZ = body.minZ - PROBE_DEPTH;
            } else {
                minZ = body.maxZ;
                maxZ = body.maxZ + PROBE_DEPTH;
            }
        }

        if (!(maxX > minX) || !(maxY > minY) || !(maxZ > minZ)) {
            return null;
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether any block collision geometry intersects this local slab.
     *
     * <p>Tested against the real voxel shape rather than the block's bounding box, so a slab, a
     * stair or a fence answers about the part of itself that is actually there. That precision is
     * the point: an approximate contact test produces phantom walls, and a phantom wall is exactly
     * the failure this class exists to remove.</p>
     */
    private static boolean occupied(final Level level, final AABB slab) {
        final int minX = (int) Math.floor(slab.minX);
        final int minY = (int) Math.floor(slab.minY);
        final int minZ = (int) Math.floor(slab.minZ);
        final int maxX = (int) Math.floor(slab.maxX);
        final int maxY = (int) Math.floor(slab.maxY);
        final int maxZ = (int) Math.floor(slab.maxZ);

        // A pathological pose could hand us an enormous range; refusing is better than freezing.
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 512L) {
            return false;
        }

        final VoxelShape probe = Shapes.create(slab);
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);

                    // Never force a chunk load from a probe. An unloaded chunk is not a contact.
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

                    if (Shapes.joinIsNotEmpty(shape.move(x, y, z), probe, BooleanOp.AND)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
