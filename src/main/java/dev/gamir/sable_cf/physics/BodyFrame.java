package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * Which way the body is pointing, and everything that depends on it.
 *
 * <p>One of these lives on every entity (added by {@code EntityMixin}). It is the single source of
 * truth for contacts, the surface normal, the normal load and the body orientation, so the hitbox,
 * the physics and the camera cannot disagree with each other.
 *
 * <h2>Why this runs on both sides</h2>
 *
 * <p>The orientation is read by Sable's collision path, which runs on both sides. Rather than
 * networking a quaternion every tick, this derives it from data both sides already have: Sable's
 * sub-level pose. Same inputs, same formula, same answer, no packets, and no way for the two to
 * drift apart. That is the entire reason this class holds no client-only types.</p>
 *
 * <h2>What it supplies to the hitbox, and what it deliberately does not</h2>
 *
 * <p>It supplies an <b>orientation</b> and stops there. It used to also re-fit a rotated box into
 * an axis-aligned one, which necessarily widened it - the source of the wedging warning that used
 * to be in the README, and of players being shoved out of geometry.</p>
 *
 * <p>That was never needed. Sable's {@code SubLevelEntityCollision} takes the entity's
 * <i>unrotated</i> {@code getXsize/getYsize/getZsize}, combines them with the quaternion from
 * {@code getCustomEntityOrientation}, and runs SAT against sub-level blocks - genuine oriented-box
 * collision. It expands its own broadphase by the eye height, and it pivots the body about eye
 * height rather than the feet. So a hand-rolled enclosing AABB was not merely redundant, it
 * disagreed with Sable's own pivot, and since it fed vanilla's box it could only ever act against
 * <i>main-level</i> geometry - which is exactly the wedging. Rotating is the whole job.</p>
 *
 * <h2>Where the frame acceleration comes from, and why it is filtered</h2>
 *
 * <p>Sable's {@code getVelocity} is sampled at a <i>fixed material point</i> of the sub-level on
 * two consecutive ticks and differenced. Because the point is fixed in the sub-level rather than in
 * the world, that difference is the material acceleration of the deck under you, and it already
 * contains the centrifugal term, the Euler term, and the plot's linear acceleration, with the
 * rotation centre and scale handled by Sable.</p>
 *
 * <p>It is also a <i>second</i> difference of a pose that arrives over the network and is
 * interpolated on the way in, which makes it noisy in a way no amount of correct algebra fixes.
 * Hence the low-pass and the dead zone in {@link CfConfig}: below the dead zone the value is
 * exactly zero, so a sub-level that is merely travelling contributes literally nothing.</p>
 */
public final class BodyFrame {

    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    private static final double IDENTITY_EPSILON = 1.0e-10;

    /** Ticks the attach is suppressed for after a deliberate release. */
    private static final int RELEASE_COOLDOWN = 10;

    // ---------------------------------------------------------------- outputs

    private boolean active;

    /** Rotation taking world up to body up. Identity when standing upright. */
    private final Quaterniond orientation = new Quaterniond();

    private final Vector3d normal = new Vector3d(0.0, 1.0, 0.0);
    private final Vector3d apparent = new Vector3d(0.0, -CfConfig.GRAVITY, 0.0);

    /** Filtered and dead-zoned. This is what every consumer should use. */
    private final Vector3d frameAcceleration = new Vector3d();

    /** Unfiltered. Only for release detection, where a genuine spike is the signal. */
    private final Vector3d frameAccelerationRaw = new Vector3d();

    private final Vector3d filtered = new Vector3d();
    private final Vector3d omega = new Vector3d();
    private final Vector3d angularAcceleration = new Vector3d();

    /** Rigid translation of the sub-level, m/s: the bit that is NOT rotation. */
    private final Vector3d deckTranslation = new Vector3d();

    private final ContactProbe contacts = new ContactProbe();

    private double press;
    private double tilt;
    private double spinGate;
    private double frameShare;

    private boolean attached;
    private int attachedIndex = -1;
    private final Vector3d attachNormal = new Vector3d(0.0, 1.0, 0.0);

    private boolean releaseTriggered;
    private int releaseCooldown;
    private final Vector3d releaseVelocity = new Vector3d();

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

    public Vector3dc frameAccelerationRaw() {
        return this.frameAccelerationRaw;
    }

    public Vector3dc omega() {
        return this.omega;
    }

    public Vector3dc angularAcceleration() {
        return this.angularAcceleration;
    }

    /**
     * Rigid translation of the sub-level, m/s.
     *
     * <p>Subtracting this is what stops a cruising deck from behaving like a gale. The material
     * point at the pose's rotation centre is the one point rotation leaves fixed, so its velocity
     * is the translation and nothing else.</p>
     */
    public Vector3dc deckTranslation() {
        return this.deckTranslation;
    }

    public ContactProbe contacts() {
        return this.contacts;
    }

    /** Normal load from the deck terms alone, m/s^2. */
    public double press() {
        return this.press;
    }

    /** 0 = upright, 1 = fully aligned with the surface. */
    public double tilt() {
        return this.tilt;
    }

    /** How much of the rotation-specific behaviour applies right now, 0..1. */
    public double spinGate() {
        return this.spinGate;
    }

    /**
     * Fraction of the normal load that the ride is supplying rather than gravity, 0..1.
     *
     * <p>The most load-bearing number in the mod. Standing on a deck - level, sloped, moving,
     * accelerating - this is near zero, and everything downstream that could tip or shove you is
     * scaled by it. Pinned inside a drum it is near one.</p>
     */
    public double frameShare() {
        return this.frameShare;
    }

    public boolean isAttached() {
        return this.attached;
    }

    public Vector3dc attachNormal() {
        return this.attachNormal;
    }

    /** True for exactly one tick after a hard sub-level stall while attached. */
    public boolean releaseTriggered() {
        return this.releaseTriggered;
    }

    /** Deck velocity the player should keep when released, m/s. */
    public Vector3dc releaseVelocity() {
        return this.releaseVelocity;
    }

    public void consumeRelease() {
        this.releaseTriggered = false;
    }

    /** Lets go on purpose - jumping off a drum wall, for instance. */
    public void detach() {
        this.attached = false;
        this.attachedIndex = -1;
        this.releaseCooldown = Math.max(this.releaseCooldown, RELEASE_COOLDOWN);
    }

    // ---------------------------------------------------------------- tick

    /**
     * Recomputes everything. Called before the entity moves, on both sides, for players.
     */
    public void tick(final Entity entity) {
        this.releaseTriggered = false;

        if (this.releaseCooldown > 0) {
            this.releaseCooldown--;
        }

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

        // The rotation centre in local space. transformPosition maps it to pose.position(), so it
        // is the one material point rotation cannot move: its velocity is pure translation.
        final Vec3 translation = SableAccess.localPointVelocity(subLevel, pose.rotationPoint());

        this.deckTranslation.set(translation.x, translation.y, translation.z);

        if (!this.deckTranslation.isFinite()) {
            this.deckTranslation.zero();
        }

        if (this.anchor == null || !this.anchor.equals(id)) {
            // Fresh sub-level: there is no previous sample, and inventing one is how you get a
            // one-tick kick every time somebody steps onto a contraption.
            this.anchor = id;
            this.primed = false;
            this.frameAcceleration.zero();
            this.frameAccelerationRaw.zero();
            this.filtered.zero();
            this.omega.zero();
            this.previousOmega.zero();
            this.angularAcceleration.zero();
            this.attached = false;
            this.attachedIndex = -1;
        } else if (this.primed) {
            // Velocity of LAST tick's material point, evaluated with THIS tick's pose. Same point
            // of the deck at two times, so the difference is a real acceleration.
            final Vec3 nowAtOldPoint = SableAccess.localPointVelocity(subLevel, this.lastLocalPoint);

            this.frameAccelerationRaw.set(
                    (nowAtOldPoint.x - this.lastPointVelocity.x) * 20.0,
                    (nowAtOldPoint.y - this.lastPointVelocity.y) * 20.0,
                    (nowAtOldPoint.z - this.lastPointVelocity.z) * 20.0);

            if (!this.frameAccelerationRaw.isFinite()) {
                this.frameAccelerationRaw.zero();
            }

            this.detectRelease(entity);

            // Low-pass, then dead zone. The filter makes the pose noise small; the dead zone makes
            // it nothing, so "standing on a moving platform" is arithmetically identical to
            // standing on the ground rather than merely close to it.
            final double alpha = CfConfig.smoothingAlpha(CfConfig.FRAME_ACCEL_HALF_LIFE, 1.0 / 20.0);

            this.filtered.lerp(this.frameAccelerationRaw, alpha);

            if (!this.filtered.isFinite()) {
                this.filtered.zero();
            }

            final double gate = CfConfig.frameAccelGate(this.filtered.length());

            this.frameAcceleration.set(this.filtered).mul(gate);

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

        this.spinGate = CfConfig.spinGate(this.omega.length());

        // --- apparent gravity ---

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

        // --- contacts and surface ---

        this.contacts.probe(entity, subLevel);

        final boolean hasSurface = SurfaceEstimator.estimate(this.contacts, down, this.normal);

        if (!hasSurface) {
            // Airborne. Nothing is holding you, so nothing may tilt or grip you. Enforced here
            // rather than trusted to thresholds elsewhere.
            this.press = 0.0;
            this.frameShare = 0.0;
            this.attached = false;
            this.attachedIndex = -1;
            this.relax(0.0);
            this.active = true;
            return;
        }

        this.press = Math.max(0.0, -this.apparent.dot(this.normal));

        final double ridePress = Math.max(0.0,
                -(this.frameAcceleration.x * strength * this.normal.x
                        + this.frameAcceleration.y * strength * this.normal.y
                        + this.frameAcceleration.z * strength * this.normal.z));

        this.frameShare = this.press > 1.0e-6
                ? Math.min(1.0, ridePress / this.press)
                : 0.0;

        this.updateAttachment(strength);

        // --- tilt ---

        // Scaled by frameShare, and that is the whole regression fix. A person standing on a slope
        // stands UPRIGHT - they do not grow perpendicular to the hillside - and a deck that is
        // merely travelling is a slope. Only a ride actively pressing you into a surface rotates a
        // body, so the tilt is gated on the ride's share of the load rather than on the load.
        // Falls out for free: the partial tilt during a floor-to-wall climb, since the share ramps
        // up as the centrifugal load takes over from gravity.
        final double rideWeight = CfConfig.smoothstep(this.frameShare, 0.15, 0.6);
        final double targetTilt = CfConfig.tiltFromPress(this.press) * rideWeight;

        this.relax(targetTilt);

        this.active = true;
    }

    /**
     * Moves the orientation towards {@code targetTilt} of the way to the surface normal.
     */
    private void relax(final double targetTilt) {
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

        final double alpha = CfConfig.smoothingAlpha(CfConfig.BODY_HALF_LIFE, 1.0 / 20.0);

        this.orientation.slerp(this.targetOrientation, alpha).normalize();

        if (!Double.isFinite(this.orientation.w)) {
            this.orientation.identity();
        }

        final double surfaceAngle = angleOf(this.normal);

        this.tilt = surfaceAngle > 1.0e-3
                ? Math.min(1.0, this.orientation.angle() / surfaceAngle)
                : 0.0;
    }

    /**
     * Decides whether the player is latched to a wall.
     *
     * <p>Three conditions, and each rules out a specific wrong behaviour:</p>
     *
     * <ul>
     *   <li><b>It is a wall.</b> A real side contact, from the probe - not a direction inferred
     *       from a force. Floors do not need latching and ceilings are not footing.</li>
     *   <li><b>The ride is pressing you into it, not you.</b> {@code frameShare} above
     *       {@code attach_share}. This is the answer to "only in the right scenarios": a drum
     *       throwing you outward passes easily, walking into a wall on a calm contraption cannot,
     *       so ordinary walls stay ordinary and you can still bounce off them.</li>
     *   <li><b>Hard enough, with hysteresis.</b> Latches at {@code attach_press_g} and lets go
     *       below {@code release_press_g}. Equal thresholds would flicker every tick at the
     *       boundary, which is the coin-flip stickiness this replaces.</li>
     * </ul>
     *
     * <p>Rotation-gated, so nothing that is not spinning can latch anyone.</p>
     */
    private void updateAttachment(final double strength) {
        if (!CfConfig.GRIP_ENABLED.get() || this.releaseCooldown > 0 || this.spinGate <= 0.0) {
            this.attached = false;
            this.attachedIndex = -1;
            return;
        }

        final Vector3d down = new Vector3d(this.apparent);

        if (down.lengthSquared() < 1.0e-9) {
            down.set(0.0, -1.0, 0.0);
        } else {
            down.normalize();
        }

        final int best = SurfaceEstimator.bestContact(this.contacts, down);

        if (best < 0) {
            this.attached = false;
            this.attachedIndex = -1;
            return;
        }

        final Vector3dc candidate = this.contacts.normal(best);

        // A wall, not a floor or a ceiling.
        if (Math.abs(candidate.dot(WORLD_UP)) >= CfConfig.WALL_COSINE) {
            this.attached = false;
            this.attachedIndex = -1;
            return;
        }

        final double into = Math.max(0.0, -(
                this.frameAcceleration.x * strength * candidate.x()
                        + this.frameAcceleration.y * strength * candidate.y()
                        + this.frameAcceleration.z * strength * candidate.z()));

        final double total = Math.max(0.0, -this.apparent.dot(candidate));
        final double share = total > 1.0e-6 ? into / total : 0.0;

        if (share < CfConfig.ATTACH_SHARE.get()) {
            this.attached = false;
            this.attachedIndex = -1;
            return;
        }

        final double g = CfConfig.GRAVITY;
        final double threshold = this.attached && best == this.attachedIndex
                ? CfConfig.ATTACH_RELEASE_G.get() * g
                : CfConfig.ATTACH_PRESS_G.get() * g;

        if (into * this.spinGate >= threshold) {
            this.attached = true;
            this.attachedIndex = best;
            this.attachNormal.set(candidate);
        } else {
            this.attached = false;
            this.attachedIndex = -1;
        }
    }

    /**
     * Spots the sub-level stopping dead while you were attached to it.
     *
     * <p>The lift-jams-against-a-frame case. The deck was carrying you up at speed and is suddenly
     * not; you were never attached to the world, only to the deck, so the honest outcome is that
     * you keep going.</p>
     *
     * <p>Reads the <i>raw</i> acceleration on purpose: a genuine hard stop is an enormous spike,
     * and the filter that protects everything else from noise would blunt exactly the signal this
     * needs. Requiring attachment first is what keeps it from ever becoming "players bounce off
     * contraptions".</p>
     */
    private void detectRelease(final Entity entity) {
        if (!CfConfig.RELEASE_ENABLED.get() || !this.attached || this.releaseCooldown > 0) {
            return;
        }

        final double speed = this.lastPointVelocity.length();

        if (speed < CfConfig.RELEASE_MIN_SPEED.get()) {
            return;
        }

        final Vector3d direction = new Vector3d(this.lastPointVelocity).div(speed);

        // Deceleration ALONG the direof travel. A turn is a large sideways acceleration and must
        // not count; only losing the motion you had counts.
        final double along = this.frameAccelerationRaw.dot(direction);

        if (along > -CfConfig.RELEASE_DECEL_G.get() * CfConfig.GRAVITY) {
            return;
        }

        this.releaseVelocity.set(this.lastPointVelocity);
        this.releaseTriggered = true;
        this.releaseCooldown = RELEASE_COOLDOWN;
        this.attached = false;
        this.attachedIndex = -1;
    }

    private void sampleOmega(final Quaterniondc orientation) {
        final Quaterniond delta = new Quaterniond(orientation)
                .mul(new Quaterniond(this.lastPoseOrientation).invert())
                .normalize();

        // Converting a near-identity quaternion to axis-angle divides by a vanishing sine and
        // produces NaN, and one NaN in a velocity is a permanently frozen player.
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
        this.frameShare = 0.0;
        this.spinGate = 0.0;
        this.attached = false;
        this.attachedIndex = -1;
        this.frameAcceleration.zero();
        this.frameAccelerationRaw.zero();
        this.filtered.zero();
        this.omega.zero();
        this.previousOmega.zero();
        this.angularAcceleration.zero();
        this.deckTranslation.zero();
        this.contacts.clear();
        this.normal.set(0.0, 1.0, 0.0);
        this.apparent.set(0.0, -CfConfig.GRAVITY, 0.0);

        // Unwind rather than snap: stepping off a drum should let you stand up, not teleport you
        // upright.
        if (this.orientation.angle() > 1.0e-4) {
            this.orientation
                    .slerp(new Quaterniond(),
                            CfConfig.smoothingAlpha(CfConfig.BODY_HALF_LIFE, 1.0 / 20.0))
                    .normalize();
            this.tilt = Math.max(0.0, this.tilt - 0.08);
            this.active = true;
        } else {
            this.orientation.identity();
            this.tilt = 0.0;
            this.active = false;
        }
    }

    /**
     * The orientation to hand Sable's collision path, scaled by {@code hitbox.amount}.
     *
     * <p>An orientation and nothing else. Sable does the oriented-box work.</p>
     *
     * @return the orientation, or null to leave the body upright
     */
    public Quaterniondc collisionOrientation() {
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

        return used.normalize();
    }

    /** Angle between a unit normal and world up, radians. */
    private static double angleOf(final Vector3dc normal) {
        return Math.acos(Math.min(1.0, Math.max(-1.0, normal.y())));
    }
}
