package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Which surface is currently "the floor".
 *
 * <h2>Why the plane is chosen discretely</h2>
 *
 * <p>The old code blended the six candidate normals with a softmax weighted by how well each
 * opposed felt-down. It reads as the smooth, principled option and it is the direct cause of two of
 * the four reported problems:</p>
 *
 * <ul>
 *   <li>In a floor/wall corner - which is every corner of every room - two faces score similarly,
 *       so the blend returns a <b>diagonal</b> normal pointing into neither surface. Everything
 *       downstream then pushed the player along a 45 degree vector that no geometry supported:
 *       "sideways drift" and "lifted up" are the two halves of that same vector.</li>
 *   <li>The weights move continuously with the forces, so an oscillating force gives an oscillating
 *       normal, and the body chases it. That is the shaking.</li>
 * </ul>
 *
 * <p>But there is no such thing as a diagonal floor. You are standing on the deck or on the wall,
 * and the transition is an event, not a mixture. So the choice here is discrete, and the smoothing
 * that used to be applied to the <i>normal</i> is applied to the <i>body</i> instead - which is the
 * right place for it, because a body turning over 0.13 s looks like a person catching their
 * balance, while a normal drifting over 0.13 s is a lie about where the wall is.</p>
 *
 * <h2>Committing, and staying committed</h2>
 *
 * <p>Two independent guards, because a mid-air flicker back and forth is worse than either wrong
 * answer on its own:</p>
 *
 * <ol>
 *   <li>A challenger must beat the incumbent by {@code plane.switch_margin_g} of gravity. Ties, and
 *       near-ties, go to whatever you are already standing on.</li>
 *   <li>It must keep beating it for {@code plane.dwell_ticks} ticks in a row. One tick of noise
 *       cannot flip the world over.</li>
 * </ol>
 *
 * <p>Losing contact is different and is acted on immediately: a face you are not touching is not a
 * floor, no matter how hard you are being pressed towards where it used to be. Waiting out the
 * dwell there would keep the body lying sideways in mid-air after being flung off a loop.</p>
 *
 * <h2>The rotation, and the 360 loop</h2>
 *
 * <p>The plane's orientation is the minimal rotation from world up to the chosen normal. That
 * choice is what makes a full loop work correctly, which is worth spelling out because it looks
 * like it should not: for a normal sweeping the XY plane, {@code rotationTo} yields
 * {@code Rz(-theta)} on the way up and {@code Rz(360 - theta)} on the way down, and those are the
 * same rotation. So the orientation traverses the entire circle continuously, with no seam at the
 * top - and it does it without dragging the deck's yaw along, which would fight the yaw
 * compensation Sable already performs.</p>
 *
 * <p>The single genuine singularity is dead overhead, where up and the normal are exactly opposite
 * and the axis is undefined for one tick. The previous axis is reused there, so the body keeps
 * turning the way it was already turning instead of picking whatever JOML's fallback happens to
 * be.</p>
 */
public final class GravityPlane {

    public static final int NONE = -1;

    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    private final Vector3d normal = new Vector3d(0.0, 1.0, 0.0);
    private final Quaterniond rotation = new Quaterniond();

    /** Remembered swing axis, so the one degenerate tick overhead continues rather than guesses. */
    private final Vector3d lastAxis = new Vector3d(1.0, 0.0, 0.0);

    private int committed = NONE;
    private int challenger = NONE;
    private int challengerTicks;

    private double support;
    private double challengerSupport;

    public void reset() {
        this.committed = NONE;
        this.challenger = NONE;
        this.challengerTicks = 0;
        this.support = 0.0;
        this.challengerSupport = 0.0;
        this.normal.set(0.0, 1.0, 0.0);
        this.rotation.identity();
        this.lastAxis.set(1.0, 0.0, 0.0);
    }

    /**
     * Re-decides the floor.
     *
     * @param probe    this tick's contacts; null or empty means airborne
     * @param apparent felt acceleration in the rotating frame, m/s^2, gravity included. A face is
     *                 a floor to the extent that this pushes you INTO it.
     */
    public void update(@Nullable final ContactProbe probe, final Vector3dc apparent,
                       final boolean enabled) {

        if (!enabled || probe == null || !probe.any() || !apparent.isFinite()) {
            this.release();
            return;
        }

        // Score every contacting face by how hard the felt acceleration presses into it. A face
        // that is being pulled away from scores negative and can never win, but CAN still be held
        // if it is the incumbent - which is exactly the top of a loop, where the only thing keeping
        // you on the deck is that you were already on it.
        int best = NONE;
        double bestSupport = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < ContactProbe.AXIS_COUNT; i++) {
            if (!probe.contact(i)) {
                continue;
            }

            final double press = -apparent.dot(probe.normal(i));

            if (press > bestSupport) {
                bestSupport = press;
                best = i;
            }
        }

        if (best == NONE) {
            this.release();
            return;
        }

        if (this.committed == NONE) {
            this.commit(best, bestSupport, probe.normal(best));
            return;
        }

        if (!probe.contact(this.committed)) {
            // Not touching it any more. No dwell, no margin: it is not a floor.
            this.commit(best, bestSupport, probe.normal(best));
            return;
        }

        final double incumbentSupport = -apparent.dot(probe.normal(this.committed));

        this.support = incumbentSupport;
        this.setNormal(probe.normal(this.committed));

        if (best == this.committed) {
            this.challenger = NONE;
            this.challengerTicks = 0;
            this.challengerSupport = 0.0;
            return;
        }

        final double margin = CfConfig.PLANE_SWITCH_MARGIN_G.get() * CfConfig.GRAVITY;

        if (bestSupport < incumbentSupport + margin) {
            this.challenger = NONE;
            this.challengerTicks = 0;
            this.challengerSupport = 0.0;
            return;
        }

        if (best != this.challenger) {
            this.challenger = best;
            this.challengerTicks = 1;
            this.challengerSupport = bestSupport;
            return;
        }

        this.challengerTicks++;
        this.challengerSupport = bestSupport;

        if (this.challengerTicks >= Math.max(1, CfConfig.PLANE_DWELL_TICKS.getAsInt())) {
            this.commit(best, bestSupport, probe.normal(best));
        }
    }

    /** Give up the floor: airborne, or the ride stopped. */
    public void release() {
        this.committed = NONE;
        this.challenger = NONE;
        this.challengerTicks = 0;
        this.support = 0.0;
        this.challengerSupport = 0.0;
        this.normal.set(0.0, 1.0, 0.0);
        this.rotation.identity();
    }

    private void commit(final int index, final double press, final Vector3dc worldNormal) {
        this.committed = index;
        this.challenger = NONE;
        this.challengerTicks = 0;
        this.challengerSupport = 0.0;
        this.support = press;

        this.setNormal(worldNormal);
    }

    private void setNormal(final Vector3dc worldNormal) {
        if (!worldNormal.isFinite()) {
            return;
        }

        final double length = worldNormal.length();

        if (length < 1.0e-9) {
            return;
        }

        this.normal.set(worldNormal).div(length);

        final double alignment = this.normal.dot(WORLD_UP);

        if (alignment > CfConfig.ANTIPARALLEL_COSINE) {
            this.rotation.rotationTo(WORLD_UP, this.normal).normalize();

            // Remember the swing axis while it is well defined, so that the one tick where it is
            // not can continue the same turn instead of jumping to an unrelated axis.
            final Vector3d axis = new Vector3d(WORLD_UP).cross(this.normal);

            if (axis.lengthSquared() > 1.0e-8) {
                this.lastAxis.set(axis.normalize());
            }
        } else {
            // Dead overhead. Half a turn about the last axis we saw, which is the continuation of
            // whatever arc got us here.
            this.rotation.rotationAxis(Math.PI, this.lastAxis.x, this.lastAxis.y, this.lastAxis.z)
                    .normalize();
        }
    }

    /** Index of the committed face, or {@link #NONE}. */
    public int index() {
        return this.committed;
    }

    public boolean committed() {
        return this.committed != NONE;
    }

    /** World unit normal of the committed plane. World up when nothing is committed. */
    public Vector3dc normal() {
        return this.normal;
    }

    /** Rotation taking world up to {@link #normal()}. Identity when nothing is committed. */
    public Quaterniondc rotation() {
        return this.rotation;
    }

    /** How hard the felt acceleration presses into the committed plane, m/s^2. May be negative. */
    public double support() {
        return this.support;
    }

    public int challenger() {
        return this.challenger;
    }

    public int challengerTicks() {
        return this.challengerTicks;
    }

    public double challengerSupport() {
        return this.challengerSupport;
    }
}
