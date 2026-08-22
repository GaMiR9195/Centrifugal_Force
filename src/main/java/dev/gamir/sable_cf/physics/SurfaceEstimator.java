package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Which way is "up out of the surface you are standing on", in world space.
 *
 * <h2>Why not a raycast</h2>
 *
 * <p>Hits on sub-level blocks come back in the sub-level's own coordinate space, millions of blocks
 * from the player, so every result would need converting and disambiguating. A deck is block
 * geometry, so its normal is always one of the sub-level's six local axes - and picking among six
 * known candidates is both cheaper and exact.</p>
 *
 * <h2>Why a blend and not the best one</h2>
 *
 * <p>Taking the single most-opposed axis makes the floor-to-wall handover a step function: one tick
 * you are on the floor, the next the wall is the floor, and the body snaps ninety degrees. What is
 * wanted is driving up a rounded ramp.</p>
 *
 * <p>So instead the six axes are weighted by {@code exp(k * alignment)} and summed. Two facts make
 * this exactly the rounded ramp:</p>
 *
 * <ul>
 *   <li>When felt-down clearly favours one axis, that weight dominates and the result is that axis.
 *       Nothing changes on an ordinary deck.</li>
 *   <li>When felt-down is halfway between the floor and the wall, both weights are equal and the
 *       result is their bisector - a surface at 45 degrees. Sweep felt-down from down to sideways
 *       and the normal sweeps continuously with it.</li>
 * </ul>
 *
 * <p>{@code k} controls the corner radius: low is a gentle curve, high approaches the old switch.</p>
 *
 * <h2>Known limitation</h2>
 *
 * <p>This is the normal of the sub-level's <i>frame</i>, not of the specific block face under your
 * feet, so slabs, stairs and any non-axis-aligned geometry read as their containing box. Sable does
 * keep a real contact manifold, but only behind an {@code @ApiStatus.Internal} accessor - that is
 * upstream ask #2 in {@code docs/UPSTREAM.md}.</p>
 */
public final class SurfaceEstimator {

    private static final Vector3dc[] LOCAL_AXES = {
            new Vector3d(0.0, 1.0, 0.0), new Vector3d(0.0, -1.0, 0.0),
            new Vector3d(1.0, 0.0, 0.0), new Vector3d(-1.0, 0.0, 0.0),
            new Vector3d(0.0, 0.0, 1.0), new Vector3d(0.0, 0.0, -1.0),
    };

    /**
     * @param pose the sub-level pose whose local axes are the candidates
     * @param down unit vector for felt-down, world space
     * @param dest receives the unit surface normal, world space
     */
    public static Vector3d estimate(final Pose3dc pose, final Vector3dc down, final Vector3d dest) {
        final double sharpness = CfConfig.SURFACE_BLEND_SHARPNESS;

        final double[] alignment = new double[LOCAL_AXES.length];
        final Vector3d[] world = new Vector3d[LOCAL_AXES.length];

        double best = -Double.MAX_VALUE;

        for (int i = 0; i < LOCAL_AXES.length; i++) {
            final Vector3d transformed = pose.transformNormal(LOCAL_AXES[i], new Vector3d());
            final double length = transformed.length();

            if (length < 1.0e-9 || !transformed.isFinite()) {
                world[i] = null;
                alignment[i] = -Double.MAX_VALUE;
                continue;
            }

            // transformNormal carries the sub-level's scale, so normalise before comparing.
            transformed.div(length);
            world[i] = transformed;

            alignment[i] = -transformed.dot(down);

            if (alignment[i] > best) {
                best = alignment[i];
            }
        }

        if (best == -Double.MAX_VALUE) {
            return dest.set(0.0, 1.0, 0.0);
        }

        dest.zero();

        for (int i = 0; i < LOCAL_AXES.length; i++) {
            if (world[i] == null) {
                continue;
            }

            // Subtracting the best alignment first is the standard softmax shift: it cannot change
            // the ratios but it keeps exp() away from overflow.
            final double weight = Math.exp(sharpness * (alignment[i] - best));

            if (weight > 1.0e-6) {
                dest.add(world[i].x * weight, world[i].y * weight, world[i].z * weight);
            }
        }

        if (dest.lengthSquared() < 1.0e-12 || !dest.isFinite()) {
            return dest.set(0.0, 1.0, 0.0);
        }

        return dest.normalize();
    }

    private SurfaceEstimator() {
    }
}
