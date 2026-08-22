package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Turns a set of real contacts into one surface normal, smoothly.
 *
 * <h2>What changed, and why it matters</h2>
 *
 * <p>This used to blend all six of the sub-level's axes, weighted by how well each opposed
 * felt-down. That produced a normal unconditionally - including a sideways one for a player standing
 * on a flat deck with a sideways force on them, which is a wall that is not there. It is the root
 * cause of being tilted and nudged while standing still on a plain moving contraption.</p>
 *
 * <p>Now the candidates come from {@link ContactProbe}: only faces the body is genuinely touching
 * are eligible. No contacts, no normal, no tilt - and that is a guarantee from the shape of the
 * code rather than from a threshold being tuned correctly.</p>
 *
 * <h2>Why still a blend</h2>
 *
 * <p>Taking the single best contact makes the floor-to-wall handover a step function: one tick you
 * are on the floor, the next the wall is the floor, and the body snaps ninety degrees. What is
 * wanted is driving up a rounded ramp.</p>
 *
 * <p>So contacts are weighted by {@code exp(k * alignment)} with felt-up and summed. Two facts make
 * that exactly the rounded ramp:</p>
 *
 * <ul>
 *   <li>When one contact clearly opposes felt-down, its weight dominates and the result is that
 *       face. Ordinary standing is unaffected.</li>
 *   <li>In the corner where floor meets wall - the one place both are genuinely touched - the
 *       weights are comparable and the result sweeps continuously through the bisector as felt-down
 *       rotates. That is the ramp, and it only exists where the geometry justifies it.</li>
 * </ul>
 *
 * <p>{@code k} sets the corner radius: low is a gentle curve, high approaches the old switch.</p>
 */
public final class SurfaceEstimator {

    /**
     * @param contacts the probed contact set
     * @param down     unit vector for felt-down, world space
     * @param dest     receives the unit surface normal, world space
     * @return true if a surface was found; false leaves {@code dest} as world up
     */
    public static boolean estimate(
            final ContactProbe contacts, final Vector3dc down, final Vector3d dest) {

        dest.set(0.0, 1.0, 0.0);

        if (!contacts.any()) {
            return false;
        }

        double best = -Double.MAX_VALUE;

        for (int i = 0; i < ContactProbe.AXIS_COUNT; i++) {
            if (!contacts.contact(i)) {
                continue;
            }

            final double alignment = -contacts.normal(i).dot(down);

            if (alignment > best) {
                best = alignment;
            }
        }

        if (best == -Double.MAX_VALUE) {
            return false;
        }

        final double sharpness = CfConfig.SURFACE_BLEND_SHARPNESS;
        final Vector3d sum = new Vector3d();

        for (int i = 0; i < ContactProbe.AXIS_COUNT; i++) {
            if (!contacts.contact(i)) {
                continue;
            }

            final Vector3dc normal = contacts.normal(i);
            final double alignment = -normal.dot(down);

            // Subtracting the best alignment first is the standard softmax shift: it cannot change
            // the ratios but it keeps exp() away from overflow.
            final double weight = Math.exp(sharpness * (alignment - best));

            if (weight > 1.0e-6) {
                sum.add(normal.x() * weight, normal.y() * weight, normal.z() * weight);
            }
        }

        if (sum.lengthSquared() < 1.0e-12 || !sum.isFinite()) {
            return false;
        }

        dest.set(sum.normalize());

        return true;
    }

    /**
     * The contacting face that best opposes felt-down, without blending.
     *
     * <p>Used where a single definite face is needed rather than a smooth average - deciding which
     * wall has latched you, for instance, since "attached to the bisector of two walls" is not a
     * thing.</p>
     *
     * @return the index of the best contact, or -1 if there are none
     */
    public static int bestContact(final ContactProbe contacts, final Vector3dc down) {
        int best = -1;
        double bestAlignment = -Double.MAX_VALUE;

        for (int i = 0; i < ContactProbe.AXIS_COUNT; i++) {
            if (!contacts.contact(i)) {
                continue;
            }

            final double alignment = -contacts.normal(i).dot(down);

            if (alignment > bestAlignment) {
                bestAlignment = alignment;
                best = i;
            }
        }

        return best;
    }

    private SurfaceEstimator() {
    }
}
