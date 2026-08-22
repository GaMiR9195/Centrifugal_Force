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
 * <h2>The sign that matters</h2>
 *
 * <p>{@code apparent = gravity - a_frame}. Therefore the ride's share of the normal load is
 * {@code +(a_frame . n)}, because {@code press = -apparent . n = -(gravity . n) + (a_frame . n)}.
 * Getting that backwards zeroes the ride share on exactly the surface where it should be largest -
 * a drum wall, where the deck's centripetal acceleration and the wall normal both point inward -
 * and a zero ride share silently disables the tilt, the hitbox rotation, the attach and the climb
 * all at once. Every one of those features is downstream of this one dot product.</p>
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
 * <p>It supplies an <b>orientation</b> and stops there. Sable's {@code SubLevelEntityCollision}
 * takes the entity's <i>unrotated</i> {@code getXsize/getYsize/getZsize}, combines them with this
 * quaternion, and runs SAT against sub-level blocks - genuine oriented-box collision. It expands
 * its own broadphase by the eye height and pivots the body about eye height rather than the feet.
 * It also already snaps the box's yaw to the sub-level's grid on its own, so the body is square to
 * the contraption rather than to the world without this mod doing anything about it.</p>
 *
 * <p>So a hand-rolled enclosing AABB would not merely be redundant, it would disagree with Sable's
 * pivot, and since it feeds vanilla's box it could only ever act against <i>main-level</i>
 * geometry. Rotating is the whole job.</p>
 *
 * <h2>Where the frame acceleration comes from, and why it is filtered where it is</h2>
 *
 * <p>Sable's {@code getVelocity} is sampled at a <i>fixed material point</i> of the sub-level on
 * two consecutive ticks and differenced. Because the point is fixed in the sub-level rather than in
 * the world, that difference is the material acceleration of the deck under you, and it already
 * contains the centrifugal term, the Euler term, and the plot's linear acceleration, with the
 * rotation centre and scale handled by Sable.</p>
 *
 * <p>It is also a <i>second</i> difference of a pose that arrives over the network and is
 * interpolated on the way in, so it needs a low-pass. That low-pass runs in the <b>sub-level's own
 * frame</b>. In world space a steady spin is a rotating vector, and a first-order lag does not just
 * smooth a rotating vector, it rotates it backwards - about 16 degrees at 2.5 rad/s - which shows
 * up as a permanent sideways shove that friction has to fight. In the deck's frame the same signal
 * is constant, so the filter has nothing to lag.</p>
 */
public final class BodyFrame {

    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    private static final double IDENTITY_EPSILON = 1.0e-10;

    /** Ticks the attach is suppressed for after a deliberate release. */
    private static final int RELEASE_COOLDOWN = 10;

    /** Faster than this across the deck is a teleport or a re-anchor, not a slide. */
    private static final double MAX_DECK_RELATIVE = 100.0;

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

    /** Low-pass state, held in the SUB-LEVEL's frame. See the class note. */
    private final Vector3d filteredLocal = new Vector3d();

    private final Vector3d omega = new Vector3d();
    private final Vector3d angularAcceleration = new Vector3d();

    /** Rigid translation of the sub-level, m/s: the bit that is NOT rotation. */
    private final Vector3d deckTranslation = new Vector3d();

    /** The player's own velocity across the deck, m/s, world space. */
    private final Vector3d deckRelative = new Vector3d();

    /** A tilt axis that is defined even when the surface normal is straight down. */
    private final Vector3d tiltAxisFallback = new Vector3d(1.0, 0.0, 0.0);

    private final ContactProbe contacts = new ContactProbe();

    private double press;
    private double ridePress;
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

    /**
     * The player's velocity across the deck, m/s, world space.
     *
     * <p>Measured from the change in their <i>local</i> position, which is the only honest source.
     * Sable carries a standing player by moving their position directly, so {@code deltaMovement}
     * never contains the carry - and equally it never contains a slide caused by the carry falling
     * behind. Any speed limit written against {@code deltaMovement} therefore reads zero while the
     * player slides across the deck at three metres a second, which is how a capped outward creep
     * turned into an uncapped one.</p>
     */
    public Vector3dc deckRelativeVelocity() {
        return this.deckRelative;
    }

    public ContactProbe contacts() {
        return this.contacts;
    }

    /** Total normal load, m/s^2: gravity and the ride together. */
    public double press() {
        return this.press;
    }

    /** The part of {@link #press()} the ride is responsible for, m/s^2. */
    public double ridePress() {
        return this.ridePress;
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
     *
     * <p>Worth knowing when reading the overlay: on the flat FLOOR of a spinning drum this is also
     * near zero, and correctly so. The centrifugal vector is horizontal there, so it presses you
     * into the floor by nothing and only throws you outward. The share climbs as you reach the
     * wall, which is what makes the floor-to-wall transition a ramp rather than a switch.</p>
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

        this.updateTiltAxis(pose);

        if (this.anchor == null || !this.anchor.equals(id)) {
            // Fresh sub-level: there is no previous sample, and inventing one is how you get a
            // one-tick kick every time somebody steps onto a contraption.
            this.anchor = id;
            this.primed = false;
            this.frameAcceleration.zero();
            this.frameAccelerationRaw.zero();
            this.filteredLocal.zero();
            this.deckRelative.zero();
            this.omega.zero();
            this.previousOmega.zero();
            this.angularAcceleration.zero();
            this.attached = false;
            this.attachedIndex = -1;
        } else if (this.primed) {
            this.sampleDeckRelative(pose, localPoint);

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
            this.filterFrameAcceleration(pose);

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

        // The probe is handed the current orientation so it looks where the body actually is. One
        // tick stale, which is what breaks the circle: the tilt is derived from the contacts.
        this.contacts.probe(entity, subLevel, this.orientation);

        final boolean hasSurface = SurfaceEstimator.estimate(this.contacts, down, this.normal);

        if (!hasSurface) {
            // Airborne. Nothing is holding you, so nothing may tilt or grip you. Enforced here
            // rather than trusted to thresholds elsewhere.
            this.press = 0.0;
            this.ridePress = 0.0;
            this.frameShare = 0.0;
            this.attached = false;
            this.attachedIndex = -1;
            this.relax(0.0);
            this.active = true;
            return;
        }

        this.press = Math.max(0.0, -this.apparent.dot(this.normal));

        // POSITIVE. apparent = gravity - a_frame, so -apparent.n = -(gravity.n) + (a_frame.n) and
        // the ride's half of that is the second term as written. The inverted version of this line
        // returned zero on every drum wall in existence and took the tilt, the rotated hitbox, the
        // attach and the wall climb down with it.
        this.ridePress = Math.max(0.0, strength * (
                this.frameAcceleration.x * this.normal.x
                        + this.frameAcceleration.y * this.normal.y
                        + this.frameAcceleration.z * this.normal.z));

        this.frameShare = this.press > 1.0e-6
                ? Math.min(1.0, this.ridePress / this.press)
                : 0.0;

        this.updateAttachment(strength);

        // --- tilt ---

        final double targetTilt =
                CfConfig.tiltFromPress(this.press) * CfConfig.rideWeight(this.frameShare);

        this.relax(targetTilt);

        this.active = true;
    }

    /**
     * Low-passes the frame acceleration in the sub-level's frame, then brings it back to world.
     *
     * <p>The round trip is exact: {@code transformNormal} and {@code transformNormalInverse} are
     * inverses, scale included, so nothing is distorted by doing the filtering on the far side.</p>
     */
    private void filterFrameAcceleration(final Pose3dc pose) {
        final Vector3d local = new Vector3d();

        pose.transformNormalInverse(this.frameAccelerationRaw, local);

        if (!local.isFinite()) {
            local.zero();
        }

        final double alpha = CfConfig.smoothingAlpha(CfConfig.FRAME_ACCEL_HALF_LIFE, 1.0 / 20.0);

        this.filteredLocal.lerp(local, alpha);

        if (!this.filteredLocal.isFinite()) {
            this.filteredLocal.zero();
        }

        final Vector3d world = new Vector3d();

        pose.transformNormal(this.filteredLocal, world);

        if (!world.isFinite()) {
            this.frameAcceleration.zero();
            return;
        }

        // Dead zone applied to the world magnitude, since that is the quantity every threshold in
        // the mod is expressed in.
        this.frameAcceleration.set(world).mul(CfConfig.frameAccelGate(world.length()));
    }

    /** Differences the player's local position to get their true velocity across the deck. */
    private void sampleDeckRelative(final Pose3dc pose, final Vector3dc localPoint) {
        final Vector3d localDelta = new Vector3d(localPoint).sub(this.lastLocalPoint).mul(20.0);
        final Vector3d world = new Vector3d();

        pose.transformNormal(localDelta, world);

        if (!world.isFinite() || world.length() > MAX_DECK_RELATIVE) {
            // A re-anchor or a teleport, not a slide. Feeding it in would produce one enormous
            // sample that the limiter then believes for several ticks.
            return;
        }

        final double alpha = CfConfig.smoothingAlpha(CfConfig.DECK_RELATIVE_HALF_LIFE, 1.0 / 20.0);

        this.deckRelative.lerp(world, alpha);

        if (!this.deckRelative.isFinite()) {
            this.deckRelative.zero();
        }
    }

    /**
     * Keeps a tilt axis that stays defined when the surface normal points straight down.
     *
     * <p>Taken from the sub-level's own frame rather than from the player's facing, so it is stable
     * from tick to tick and turns with the ride instead of with the mouse.</p>
     */
    private void updateTiltAxis(final Pose3dc pose) {
        final Vector3d axis = new Vector3d();

        pose.transformNormal(new Vector3d(1.0, 0.0, 0.0), axis);
        axis.sub(0.0, axis.y, 0.0);

        if (axis.lengthSquared() < 1.0e-9 || !axis.isFinite()) {
            pose.transformNormal(new Vector3d(0.0, 0.0, 1.0), axis);
            axis.sub(0.0, axis.y, 0.0);
        }

        if (axis.lengthSquared() < 1.0e-9 || !axis.isFinite()) {
            this.tiltAxisFallback.set(1.0, 0.0, 0.0);
            return;
        }

        this.tiltAxisFallback.set(axis.normalize());
    }

    /**
     * Moves the orientation towards {@code targetTilt} of the way to the surface normal.
     */
    private void relax(final double targetTilt) {
        this.targetOrientation.identity();

        if (targetTilt > 1.0e-4) {
            final Quaterniond full = new Quaterniond();

            if (this.normal.y < CfConfig.ANTIPARALLEL_COSINE) {
                // Pinned to the top of a loop, the surface normal points straight down. A rotation
                // from world up to its exact opposite has no unique axis - every perpendicular is a
                // valid answer - and JOML picks one, so the body spins about something arbitrary.
                full.rotationAxis(Math.PI,
                        this.tiltAxisFallback.x, this.tiltAxisFallback.y, this.tiltAxisFallback.z);
            } else {
                full.rotationTo(WORLD_UP, this.normal);
            }

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
     * Decides whether the player is latched to a surface.
     *
     * <p>Three conditions, and each rules out a specific wrong behaviour:</p>
     *
     * <ul>
     *   <li><b>A real contact,</b> from the probe - not a direction inferred from a force.</li>
     *   <li><b>The ride is pressing you into it, not gravity.</b> Share above {@code attach_share}.
     *       This is the answer to "only in the right scenarios", and it is also why no test against
     *       world up is needed or wanted: an ordinary floor is held by gravity, so its share is near
     *       zero and it cannot latch, while the inside of a drum passes whether it is beside you,
     *       below you, or above you at the top of a loop. A world-up test would reject that last
     *       case as a ceiling, which is precisely the 360 degree ride this mod exists for.</li>
     *   <li><b>Hard enough, with hysteresis.</b> Latches at {@code attach_press_g} and lets go below
     *       {@code release_press_g}. Equal thresholds would flicker every tick at the boundary.</li>
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

        // Same sign convention as press: the ride's contribution is +(a_frame . n).
        final double into = Math.max(0.0, strength * (
                this.frameAcceleration.x * candidate.x()
                        + this.frameAcceleration.y * candidate.y()
                        + this.frameAcceleration.z * candidate.z()));

        final double total = Math.max(0.0, -this.apparent.dot(candidate));
        final double share = total > 1.0e-6 ? Math.min(1.0, into / total) : 0.0;

        if (share < CfConfig.ATTACH_SHARE.get()) {
            this.attached = false;
            this.attachedIndex = -1;
            return;
        }

        final double threshold = this.attached && best == this.attachedIndex
                ? CfConfig.ATTACH_RELEASE_G.get() * CfConfig.GRAVITY
                : CfConfig.ATTACH_PRESS_G.get() * CfConfig.GRAVITY;

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

        // Deceleration ALONG the direction of travel. A turn is a large sideways acceleration and
        // must not count; only losing the motion you had counts.
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
        this.ridePress = 0.0;
        this.frameShare = 0.0;
        this.spinGate = 0.0;
        this.attached = false;
        this.attachedIndex = -1;
        this.frameAcceleration.zero();
        this.frameAccelerationRaw.zero();
        this.filteredLocal.zero();
        this.deckRelative.zero();
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
     * <p>An orientation and nothing else. Sable does the oriented-box work, and it also snaps the
     * box's yaw to the sub-level's grid by itself, so the body is already square to the contraption
     * before this contributes anything.</p>
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
