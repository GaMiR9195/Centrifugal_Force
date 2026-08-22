package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4d;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * Which way the body is pointing, and everything that depends on it.
 *
 * <p>One of these lives on every entity (added by {@code EntityMixin}). It is the single source of
 * truth for the surface normal, the normal load and the body orientation, so the hitbox, the
 * physics and the camera cannot disagree with each other.
 *
 * <h2>Why this runs on both sides</h2>
 *
 * <p>A rotated collision box only works if the server agrees. If the client rotates the box and the
 * server keeps testing an upright one, the server finds you inside blocks and pushes you back out,
 * and you get rubber-banding instead of a feature.</p>
 *
 * <p>Rather than networking a quaternion every tick, this derives the orientation from data both
 * sides already have: Sable's sub-level pose. Same inputs, same formula, same answer, no packets,
 * and no way for the two to drift apart. That is the entire reason this class holds no client-only
 * types.</p>
 *
 * <h2>Where the frame acceleration comes from</h2>
 *
 * <p>Not from a hand-rolled {@code omega x (omega x r)}. That form needs the rotation centre and
 * silently omits any linear acceleration of the plot, so a contraption that speeds up in a straight
 * line produced no felt force at all, and a rotating one that was also translating produced the
 * wrong total. Both were real bugs.</p>
 *
 * <p>Instead: Sable's own {@code getVelocity} is sampled at a <i>fixed material point</i> of the
 * sub-level on two consecutive ticks and differenced. Because the point is fixed in the sub-level
 * rather than in the world, that difference is the material acceleration of the deck under you, and
 * it already contains the centrifugal term, the Euler term, and the plot's linear acceleration,
 * with the rotation centre and the scale handled by Sable. Fewer assumptions and strictly more
 * correct.</p>
 *
 * <p>Apparent gravity is then {@code g - a_frame}: the acceleration a co-moving body has to be
 * given is {@code a_frame}, so what it feels is gravity minus that. Coriolis is the one term this
 * cannot see, because it depends on the body's own velocity rather than the deck's; it is added by
 * {@link CentrifugalHandler}, which knows the player's velocity.</p>
 */
public final class BodyFrame {

    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    private static final double IDENTITY_EPSILON = 1.0e-10;

    // ---------------------------------------------------------------- outputs

    private boolean active;

    /** Rotation taking world up to body up. Identity when standing upright. */
    private final Quaterniond orientation = new Quaterniond();

    private final Vector3d normal = new Vector3d(0.0, 1.0, 0.0);
    private final Vector3d apparent = new Vector3d(0.0, -CfConfig.GRAVITY, 0.0);
    private final Vector3d frameAcceleration = new Vector3d();
    private final Vector3d omega = new Vector3d();
    private final Vector3d angularAcceleration = new Vector3d();

    private double press;
    private double tilt;

    // ---------------------------------------------------------------- sampling state

    private UUID anchor;
    private boolean primed;

    private final Vector3d lastLocalPoint = new Vector3d();
    private final Vector3d lastPointVelocity = new Vector3d();
    private final Quaterniond lastPoseOrientation = new Quaterniond();
    private final Vector3d previousOmega = new Vector3d();

    private final Quaterniond targetOrientation = new Quaterniond();

    // ---------------------------------------------------------------- reads

    public boolean isActive() {
        return this.active;
    }

    /** True when the body is tilted enough to be worth refitting a collision box for. */
    public boolean isTilted() {
        return this.active && this.tilt > 1.0e-3;
    }

    public Quaterniondc orientation() {
        return this.orientation;
    }

    public Vector3dc normal() {
        return this.normal;
    }

    /** Gravity plus the deck's fictitious terms, m/s^2. Excludes Coriolis and drag. */
    public Vector3dc apparent() {
        return this.apparent;
    }

    public Vector3dc frameAcceleration() {
        return this.frameAcceleration;
    }

    public Vector3dc omega() {
        return this.omega;
    }

    public Vector3dc angularAcceleration() {
        return this.angularAcceleration;
    }

    /** Normal load from the deck terms alone, m/s^2. */
    public double press() {
        return this.press;
    }

    /** 0 = upright, 1 = fully aligned with the surface. */
    public double tilt() {
        return this.tilt;
    }

    // ---------------------------------------------------------------- tick

    /**
     * Recomputes everything. Called before the entity moves, on both sides, for players.
     */
    public void tick(final Entity entity) {
        if (!CfConfig.SPEC.isLoaded()) {
            this.deactivate();
            return;
        }

        final SubLevel subLevel = SableAccess.tracking(entity);

        if (subLevel == null
                || subLevel.isRemoved()
                || subLevel.getLevel() != entity.level()
                || entity.isPassenger()
                || entity.isSpectator()) {
            this.deactivate();
            return;
        }

        final Pose3dc pose = subLevel.logicalPose();
        final UUID id = subLevel.getUniqueId();
        final Vec3 position = entity.position();

        final Vector3d localPoint = pose.transformPositionInverse(
                new Vector3d(position.x, position.y, position.z), new Vector3d());

        final Vec3 currentVelocity = SableAccess.localPointVelocity(subLevel, localPoint);

        if (this.anchor == null || !this.anchor.equals(id)) {
            // Fresh sub-level: there is no previous sample, and inventing one is how you get a
            // one-tick kick every time somebody steps onto a contraption.
            this.anchor = id;
            this.primed = false;
            this.frameAcceleration.zero();
            this.omega.zero();
            this.previousOmega.zero();
            this.angularAcceleration.zero();
        } else if (this.primed) {
            // Velocity of LAST tick's material point, evaluated with THIS tick's pose. Same point
            // of the deck at two times, so the difference is a real acceleration.
            final Vec3 nowAtOldPoint = SableAccess.localPointVelocity(subLevel, this.lastLocalPoint);

            this.frameAcceleration.set(
                    (nowAtOldPoint.x - this.lastPointVelocity.x) * 20.0,
                    (nowAtOldPoint.y - this.lastPointVelocity.y) * 20.0,
                    (nowAtOldPoint.z - this.lastPointVelocity.z) * 20.0);

            if (!this.frameAcceleration.isFinite()) {
                this.frameAcceleration.zero();
            }

            this.previousOmega.set(this.omega);
            this.sampleOmega(pose.orientation());

            this.angularAcceleration.set(this.omega).sub(this.previousOmega).mul(20.0);

            if (!this.angularAcceleration.isFinite()) {
                this.angularAcceleration.zero();
            }
        }

        this.lastLocalPoint.set(localPoint);
        this.lastPointVelocity.set(currentVelocity.x, currentVelocity.y, currentVelocity.z);
        this.lastPoseOrientation.set(pose.orientation());
        this.primed = true;

        // --- apparent gravity, surface, press ---

        final double strength = CfConfig.CENTRIFUGAL_ENABLED.get()
                ? CfConfig.CENTRIFUGAL_STRENGTH.get()
                : 0.0;

        this.apparent.set(0.0, -CfConfig.GRAVITY, 0.0)
                .sub(this.frameAcceleration.x * strength,
                        this.frameAcceleration.y * strength,
                        this.frameAcceleration.z * strength);

        final Vector3d down = new Vector3d(this.apparent);

        if (down.lengthSquared() < 1.0e-9 || !down.isFinite()) {
            down.set(0.0, -1.0, 0.0);
        } else {
            down.normalize();
        }

        SurfaceEstimator.estimate(pose, down, this.normal);

        this.press = -this.apparent.dot(this.normal);

        // Only a surface you are actually in contact with can hold you up. Without this you tilt
        // in mid-air, which looks like a bug even when the numbers are right.
        final boolean touching = entity.onGround()
                || entity.verticalCollision
                || entity.horizontalCollision;

        final double targetTilt = touching ? CfConfig.tiltFromPress(this.press) : 0.0;

        // --- orientation ---

        this.targetOrientation.identity();

        if (targetTilt > 1.0e-4) {
            final Quaterniond full = new Quaterniond().rotationTo(WORLD_UP, this.normal);

            if (Double.isFinite(full.x) && Double.isFinite(full.w)) {
                // Partial tilt: slerp from identity, so "halfway to the wall" is a real posture and
                // not a blend of two postures. This is the bit that makes leaning into a drum feel
                // like leaning rather than like a state change.
                this.targetOrientation.set(new Quaterniond().slerp(full.normalize(), targetTilt));
            }
        }

        final double alpha = smoothingAlpha(CfConfig.BODY_HALF_LIFE);

        this.orientation.slerp(this.targetOrientation, alpha).normalize();

        if (!Double.isFinite(this.orientation.w)) {
            this.orientation.identity();
        }

        this.tilt = this.orientation.angle() > 1.0e-4
                ? Math.min(1.0, this.orientation.angle() / Math.max(1.0e-6, angleOf(this.normal)))
                : 0.0;

        // The ratio above is only meaningful when the surface is actually tilted; when it is not,
        // there is nothing to be partway towards.
        if (angleOf(this.normal) < 1.0e-3) {
            this.tilt = 0.0;
        }

        this.active = true;
    }

    private void sampleOmega(final Quaterniondc orientation) {
        final Quaterniond delta = new Quaterniond(orientation)
                .mul(new Quaterniond(this.lastPoseOrientation).invert())
                .normalize();

        // Converting a near-identity quaternion to axis-angle divides by a vanishing sine and
        // produces NaN, and one NaN in a velocity is a permanently frozen player. Sure Footing
        // learned this on a completely static contraption.
        if (Math.abs(delta.w) >= 1.0 - IDENTITY_EPSILON) {
            this.omega.zero();
            return;
        }

        final AxisAngle4d axisAngle = new AxisAngle4d().set(delta);
        double angle = axisAngle.angle;

        // JOML reports the angle in [0, 2pi). Past half a turn the short way round is the other
        // direction, and without this a fast flip reads as a near-full-speed spin the wrong way.
        if (angle > Math.PI) {
            angle -= 2.0 * Math.PI;
        }

        this.omega.set(axisAngle.x, axisAngle.y, axisAngle.z);

        if (this.omega.lengthSquared() > 1.0e-18) {
            this.omega.normalize().mul(angle * 20.0);
        } else {
            this.omega.zero();
        }

        if (!this.omega.isFinite()) {
            this.omega.zero();
        }
    }

    private void deactivate() {
        this.anchor = null;
        this.primed = false;
        this.press = 0.0;
        this.frameAcceleration.zero();
        this.omega.zero();
        this.previousOmega.zero();
        this.angularAcceleration.zero();
        this.normal.set(0.0, 1.0, 0.0);
        this.apparent.set(0.0, -CfConfig.GRAVITY, 0.0);

        // Unwind rather than snap: stepping off a drum should let you stand up, not teleport you
        // upright, and the hitbox has to shrink back through the intermediate shapes too.
        if (this.orientation.angle() > 1.0e-4) {
            this.orientation.slerp(new Quaterniond(), smoothingAlpha(CfConfig.BODY_HALF_LIFE))
                    .normalize();
            this.tilt = Math.max(0.0, this.tilt - 0.08);
            this.active = true;
        } else {
            this.orientation.identity();
            this.tilt = 0.0;
            this.active = false;
        }
    }

    // ---------------------------------------------------------------- hitbox

    /**
     * The axis-aligned box that encloses the rotated body.
     *
     * <p>Minecraft collision is AABB-only, so a rotated body cannot be represented exactly. What
     * can be done exactly is the <i>tight enclosing AABB</i> of the rotated box, which is what this
     * is: for each world axis, the extent is the rotated half-extents projected onto it. That is
     * the standard OBB-to-AABB bound, {@code sum_j |R_ij| * h_j}.</p>
     *
     * <p>Consequences, since they are visible in play rather than theoretical:</p>
     *
     * <ul>
     *   <li>Upright, the result is bit-identical to vanilla - every term but one is multiplied by
     *       zero. Ordinary play is untouched.</li>
     *   <li>Fully on a wall, a 0.6 x 1.8 player becomes a 1.8 x 0.6 box, which is what lets you
     *       through a doorway lying down and stops you clipping into the wall you are pinned to.</li>
     *   <li>Mid-rotation the enclosing box is larger than the body - unavoidably, that is what
     *       "enclosing" means. At 45 degrees it peaks at about 1.7 blocks across. In a one-block
     *       corridor you will wedge, which is also true of a real person going horizontal in a
     *       one-block corridor, but it is the reason {@code hitbox.amount} exists.</li>
     * </ul>
     *
     * @return the refitted box, or null to keep vanilla's
     */
    public AABB fitBoundingBox(final Entity entity, final AABB vanilla) {
        if (!this.active || !CfConfig.SPEC.isLoaded() || !CfConfig.HITBOX_ENABLED.get()) {
            return null;
        }

        final double amount = CfConfig.HITBOX_AMOUNT.get();

        if (amount <= 1.0e-3 || this.orientation.angle() < 1.0e-3) {
            return null;
        }

        final Quaterniond used = amount >= 1.0
                ? new Quaterniond(this.orientation)
                : new Quaterniond().slerp(this.orientation, amount);

        if (!Double.isFinite(used.w) || used.angle() < 1.0e-3) {
            return null;
        }

        final double width = entity.getBbWidth();
        final double height = entity.getBbHeight();

        if (!(width > 0.0) || !(height > 0.0)) {
            return null;
        }

        final double hx = width * 0.5;
        final double hy = height * 0.5;
        final double hz = width * 0.5;

        final Matrix3d m = used.get(new Matrix3d());

        // JOML is column-major: mCR is column C, row R. World extent along x needs row 0.
        double ex = Math.abs(m.m00) * hx + Math.abs(m.m10) * hy + Math.abs(m.m20) * hz;
        double ey = Math.abs(m.m01) * hx + Math.abs(m.m11) * hy + Math.abs(m.m21) * hz;
        double ez = Math.abs(m.m02) * hx + Math.abs(m.m12) * hy + Math.abs(m.m22) * hz;

        // Safety net: a rotated box can never legitimately be wider than its own longest side, and
        // a NaN slipping in here would produce an infinite hitbox rather than a visible glitch.
        final double cap = Math.max(width, height) * 0.5;
        final double largest = Math.max(ex, Math.max(ey, ez));

        if (!Double.isFinite(largest) || largest <= 0.0) {
            return null;
        }

        if (largest > cap) {
            final double shrink = cap / largest;
            ex *= shrink;
            ey *= shrink;
            ez *= shrink;
        }

        // The body pivots about the feet, so the box is centred half a height along body-up rather
        // than straight up. Pinned to a wall, that puts the box out sideways from your feet, which
        // is where a person pinned to a wall actually is.
        final Vector3d up = used.transform(new Vector3d(0.0, 1.0, 0.0));

        if (!up.isFinite()) {
            return null;
        }

        final double cx = entity.getX() + up.x * hy;
        final double cy = entity.getY() + up.y * hy;
        final double cz = entity.getZ() + up.z * hy;

        final AABB box = new AABB(cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);

        return box.hasNaN() ? vanilla : box;
    }

    // ---------------------------------------------------------------- helpers

    /** Frame-rate independent smoothing factor from a half-life in seconds, per 1/20 s tick. */
    private static double smoothingAlpha(final double halfLifeSeconds) {
        if (!(halfLifeSeconds > 0.0)) {
            return 1.0;
        }

        return 1.0 - Math.pow(2.0, -(1.0 / 20.0) / halfLifeSeconds);
    }

    /** Angle between a unit normal and world up, radians. */
    private static double angleOf(final Vector3dc normal) {
        return Math.acos(Math.min(1.0, Math.max(-1.0, normal.y())));
    }
}
