package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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
 * <h2>Two orientations, not one</h2>
 *
 * <p>{@link #orientation()} is the <b>body</b>: what the camera follows and what the probe searches
 * along. It may lean all the way onto a wall, because a camera cannot penetrate anything.</p>
 *
 * <p>{@link #collisionOrientation()} is the <b>collision box</b>, and it is a separate quaternion
 * with its own slower half-life, its own hard angle cap and a clearance test in front of it. It is
 * not a scaled copy of the body, and the difference is not fussiness: Sable turns the box about the
 * player's <i>eye</i>, so every degree of lean sweeps the feet sideways by
 * {@code 2 * 1.62 * sin(A/2)} blocks. Beside a wall that is a penetration, and Sable answers a
 * penetration with a shove of the same length along body-up. See {@link Clearance}.</p>
 *
 * <h2>Where the lean target comes from</h2>
 *
 * <p>The physics normal is the softmax blend of every contacting face - smooth, which is what a
 * force wants. The <b>body target</b> is not: it is the world direction of the best single
 * contact's <i>local</i> axis, one of exactly six, so the body stands square to the sub-level grid
 * the way Sure Footing does rather than square to a blend that no face points in.</p>
 *
 * <p>It also removes a whole class of bug for free. If the face you are on is the floor, the target
 * IS world up, so the rotation is identity <i>whatever</i> the lean fraction says. Being tilted
 * while standing on a deck's floor stopped being a thing that thresholds prevent and became a thing
 * the formula cannot express.</p>
 *
 * <h2>The sign that matters</h2>
 *
 * <p>{@code apparent = gravity - a_frame}. Therefore the ride's share of the normal load is
 * {@code +(a_frame . n)}, because {@code press = -apparent . n = -(gravity . n) + (a_frame . n)}.
 * Getting that backwards zeroes the ride share on exactly the surface where it should be largest -
 * a drum wall, where the deck's centripetal acceleration and the wall normal both point inward.</p>
 *
 * <h2>Why this runs on both sides</h2>
 *
 * <p>The orientation is read by Sable's collision path, which runs on both sides. Rather than
 * networking a quaternion every tick, this derives it from data both sides already have: Sable's
 * sub-level pose. Same inputs, same formula, same answer, no packets.</p>
 */
public final class BodyFrame {

    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    private static final double IDENTITY_EPSILON = 1.0e-10;

    /** Ticks the attach is suppressed for after a deliberate release. */
    private static final int RELEASE_COOLDOWN = 10;

    /** Faster than this across the deck is a teleport or a re-anchor, not a slide. */
    private static final double MAX_DECK_RELATIVE = 100.0;

    /**
     * Fractions of a blocked collision step to retry before giving up and holding still.
     *
     * <p>Two retries rather than a binary search: this runs every tick for every player, and the
     * difference between stopping at 50% of a legal lean and at 53% of one is not observable.</p>
     */
    private static final double[] CLEARANCE_STEPS = {0.5, 0.2};

    /** Collision steps smaller than this skip the clearance test entirely. About 0.17 degrees. */
    private static final double CLEARANCE_MIN_STEP = 3.0e-3;

    // ---------------------------------------------------------------- outputs

    private boolean active;

    /** Rotation taking world up to body up. Identity when standing upright. Drives the camera. */
    private final Quaterniond orientation = new Quaterniond();

    /** The collision box's own orientation. Capped, slewed and clearance-tested separately. */
    private final Quaterniond collision = new Quaterniond();

    private final Vector3d normal = new Vector3d(0.0, 1.0, 0.0);

    /** Grid-exact world direction the body should call "up". One of the sub-level's six axes. */
    private final Vector3d bodyTarget = new Vector3d(0.0, 1.0, 0.0);

    private final Vector3d apparent = new Vector3d(0.0, -CfConfig.GRAVITY, 0.0);

    /** Filtered and dead-zoned. This is what every consumer should use. */
    private final Vector3d frameAcceleration = new Vector3d();

    /** Unfiltered. Only for release detection, where a genuine spike is the signal. */
    private final Vector3d frameAccelerationRaw = new Vector3d();

    /** Low-pass state, held in the SUB-LEVEL's frame. See {@link #filterFrameAcceleration}. */
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
    private double deviation;
    private double alignment;

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

    /** The grid-exact face the body is aiming at, world space. */
    public Vector3dc bodyTarget() {
        return this.bodyTarget;
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
     * never contains the carry - and equally never contains a slide caused by the carry falling
     * behind. Any speed limit written against {@code deltaMovement} reads zero while the player
     * slides across the deck at three metres a second.</p>
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

    /** 0 = upright, 1 = fully aligned with the target face. */
    public double tilt() {
        return this.tilt;
    }

    /** The collision box's lean, degrees. Capped by {@code hitbox.max_deg}. */
    public double hitboxDegrees() {
        final double angle = this.collision.angle();

        return Math.toDegrees(angle > Math.PI ? 2.0 * Math.PI - angle : angle);
    }

    /** How far felt gravity has left world gravity, 0..2. The gate on leaning at all. */
    public double deviation() {
        return this.deviation;
    }

    /** How well the target face opposes felt-down, -1..1. */
    public double alignment() {
        return this.alignment;
    }

    /** How much of the rotation-specific behaviour applies right now, 0..1. */
    public double spinGate() {
        return this.spinGate;
    }

    /**
     * Fraction of the normal load the ride is supplying rather than gravity, 0..1.
     *
     * <p>Standing on a deck - level, sloped, moving, accelerating - this is near zero. Pinned
     * inside a drum it is near one. It gates the attach and the wall climb.</p>
     *
     * <p>It no longer gates the LEAN, and that was the wall-entry bug: on the flat floor of a
     * spinning drum this is also near zero, correctly - the centrifugal vector is horizontal there
     * so it presses you into the floor by nothing - which meant the body stayed bolt upright right
     * up to the moment it needed not to be. Leaning is gated on {@link #deviation()} instead.</p>
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

    /** Recomputes everything. Called before the entity moves, on both sides, for players. */
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

        // The probe is handed the current BODY orientation so it looks where the body actually is.
        // One tick stale, which is what breaks the circle: the tilt is derived from the contacts.
        this.contacts.probe(entity, subLevel, this.orientation);

        final boolean hasSurface = SurfaceEstimator.estimate(this.contacts, down, this.normal);

        if (!hasSurface) {
            // Airborne. Nothing is holding you, so nothing may tilt or grip you. Enforced here
            // rather than trusted to thresholds elsewhere.
            this.press = 0.0;
            this.ridePress = 0.0;
            this.frameShare = 0.0;
            this.deviation = 0.0;
            this.alignment = 0.0;
            this.attached = false;
            this.attachedIndex = -1;
            this.bodyTarget.set(WORLD_UP);
            this.relax(0.0, entity, subLevel);
            this.active = true;
            return;
        }

        this.press = Math.max(0.0, -this.apparent.dot(this.normal));

        // POSITIVE. apparent = gravity - a_frame, so -apparent.n = -(gravity.n) + (a_frame.n).
        this.ridePress = Math.max(0.0, strength * (
                this.frameAcceleration.x * this.normal.x
                        + this.frameAcceleration.y * this.normal.y
                        + this.frameAcceleration.z * this.normal.z));

        this.frameShare = this.press > 1.0e-6
                ? Math.min(1.0, this.ridePress / this.press)
                : 0.0;

        this.updateAttachment(strength);

        // --- where the body should point, and how far it has earned the right to go ---

        final Vector3d feltUp = new Vector3d(this.apparent).negate();

        if (feltUp.lengthSquared() < 1.0e-9 || !feltUp.isFinite()) {
            feltUp.set(WORLD_UP);
        } else {
            feltUp.normalize();
        }

        // The best SINGLE contact, not the blend. Its normal is a pure local axis carried into the
        // world, so the body ends up square to the sub-level's grid rather than to an average
        // direction that no face of the contraption actually points in.
        final int best = SurfaceEstimator.bestContact(this.contacts, down);

        if (best >= 0) {
            this.bodyTarget.set(this.contacts.normal(best));
        } else {
            this.bodyTarget.set(WORLD_UP);
        }

        // How far the ride has moved your sense of down. Zero on any deck that is merely
        // travelling, however hard it accelerates, because its felt gravity IS world gravity.
        this.deviation = 1.0 - feltUp.y;
        this.alignment = this.bodyTarget.dot(feltUp);

        final double targetTilt = CfConfig.tiltFromPress(this.press)
                * CfConfig.smoothstep(this.deviation,
                        CfConfig.TILT_DEVIATION_LOW, CfConfig.TILT_DEVIATION_HIGH)
                * CfConfig.smoothstep(this.alignment,
                        CfConfig.TILT_ALIGN_LOW, CfConfig.TILT_ALIGN_HIGH);

        this.relax(targetTilt, entity, subLevel);

        this.active = true;
    }

    /**
     * Low-passes the frame acceleration in the sub-level's frame, then brings it back to world.
     *
     * <p>In world space a steady spin is a rotating vector, and a first-order lag does not just
     * smooth a rotating vector, it rotates it backwards - about 16 degrees at 2.5 rad/s - which
     * shows up as a permanent sideways shove that friction has to fight. In the deck's frame the
     * same signal is constant, so the filter has nothing to lag. The round trip is exact:
     * {@code transformNormal} and {@code transformNormalInverse} are inverses, scale included.</p>
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

        this.frameAcceleration.set(world).mul(CfConfig.frameAccelGate(world.length()));
    }

    /** Differences the player's local position to get their true velocity across the deck. */
    private void sampleDeckRelative(final Pose3dc pose, final Vector3dc localPoint) {
        final Vector3d localDelta = new Vector3d(localPoint).sub(this.lastLocalPoint).mul(20.0);
        final Vector3d world = new Vector3d();

        pose.transformNormal(localDelta, world);

        if (!world.isFinite() || world.length() > MAX_DECK_RELATIVE) {
            // A re-anchor or a teleport, not a slide.
            return;
        }

        final double alpha = CfConfig.smoothingAlpha(CfConfig.DECK_RELATIVE_HALF_LIFE, 1.0 / 20.0);

        this.deckRelative.lerp(world, alpha);

        if (!this.deckRelative.isFinite()) {
            this.deckRelative.zero();
        }
    }

    /**
     * Keeps a tilt axis that stays defined when the target points straight down.
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

    /** Moves the body towards {@code targetTilt} of the way onto its target face. */
    private void relax(final double targetTilt, final Entity entity, @Nullable final SubLevel subLevel) {
        this.targetOrientation.identity();

        if (targetTilt > 1.0e-4) {
            final Quaterniond full = new Quaterniond();

            if (this.bodyTarget.y < CfConfig.ANTIPARALLEL_COSINE) {
                // Pinned under the deck at the top of a loop: a rotation from world up to its exact
                // opposite has no unique axis, and JOML picks one, so the body spins about
                // something arbitrary unless it is told which way to go round.
                full.rotationAxis(Math.PI,
                        this.tiltAxisFallback.x, this.tiltAxisFallback.y, this.tiltAxisFallback.z);
            } else {
                full.rotationTo(WORLD_UP, this.bodyTarget);
            }

            if (Double.isFinite(full.x) && Double.isFinite(full.w)) {
                // Partial tilt: slerp from identity, so "halfway onto the wall" is a real posture
                // and not a blend of two postures. This is what makes wall entry interpolated
                // rather than a state change.
                this.targetOrientation.set(new Quaterniond().slerp(full.normalize(), targetTilt));
            }
        }

        final double alpha = CfConfig.smoothingAlpha(CfConfig.BODY_HALF_LIFE, 1.0 / 20.0);

        final Quaterniond candidate = new Quaterniond(this.orientation)
                .slerp(this.targetOrientation, alpha)
                .normalize();

        limitStep(this.orientation, candidate, Math.toRadians(CfConfig.BODY_SLEW_DEG_PER_S) / 20.0);

        this.orientation.set(candidate).normalize();

        if (!Double.isFinite(this.orientation.w)) {
            this.orientation.identity();
        }

        final double surfaceAngle = angleOf(this.bodyTarget);

        this.tilt = surfaceAngle > 1.0e-3
                ? Math.min(1.0, this.orientation.angle() / surfaceAngle)
                : 0.0;

        this.updateCollision(entity, subLevel);
    }

    /**
     * Walks the collision box towards the body, subject to a cap, a rate limit and clearance.
     *
     * <p>All three exist for the same reason and it is worth stating plainly: Sable pivots the box
     * about the eye. A 25 degree lean sweeps the feet about 0.7 blocks sideways, a 90 degree one
     * about 2.3. Rotating into a wall does not produce a nudge, it produces a minimum translation
     * vector of that length which Sable's near-vertical branch fires back along body-up.</p>
     *
     * <p>So: the cap keeps the worst case small, the rate limit keeps any single tick's sweep
     * small, and {@link Clearance} refuses outright anything that would still land inside a block.
     * A refused lean simply does not happen this tick - the body keeps leaning, the box waits, and
     * it catches up as soon as there is room. That waiting is what wall entry looks like from the
     * inside.</p>
     */
    private void updateCollision(final Entity entity, @Nullable final SubLevel subLevel) {
        final Quaterniond desired = new Quaterniond();

        final double amount = CfConfig.HITBOX_ENABLED.get() ? CfConfig.HITBOX_AMOUNT.get() : 0.0;

        if (amount > 1.0e-3 && this.orientation.angle() > 1.0e-4) {
            desired.slerp(this.orientation, amount).normalize();

            final double cap = Math.toRadians(CfConfig.HITBOX_MAX_DEG.get());

            double angle = desired.angle();

            if (angle > Math.PI) {
                angle = 2.0 * Math.PI - angle;
            }

            if (angle > cap && angle > 1.0e-9) {
                desired.set(new Quaterniond().slerp(desired, cap / angle).normalize());
            }
        }

        final double alpha = CfConfig.smoothingAlpha(CfConfig.HITBOX_HALF_LIFE, 1.0 / 20.0);

        final Quaterniond candidate = new Quaterniond(this.collision)
                .slerp(desired, alpha)
                .normalize();

        limitStep(this.collision, candidate,
                Math.toRadians(CfConfig.HITBOX_SLEW_DEG_PER_S) / 20.0);

        // Only players collide with sub-level blocks in a way this matters for, and only players
        // are worth twelve voxel lookups a tick.
        if (subLevel != null && entity instanceof Player && stepBetween(this.collision, candidate) > CLEARANCE_MIN_STEP
                && !Clearance.fits(entity, subLevel, candidate)) {

            boolean placed = false;

            for (final double fraction : CLEARANCE_STEPS) {
                final Quaterniond partial = new Quaterniond(this.collision)
                        .slerp(candidate, fraction)
                        .normalize();

                if (Clearance.fits(entity, subLevel, partial)) {
                    candidate.set(partial);
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                // Nowhere legal to move to. Holding still is always safe: the body was already
                // where it is, so it already fitted there a tick ago.
                candidate.set(this.collision);
            }
        }

        this.collision.set(candidate).normalize();

        if (!Double.isFinite(this.collision.w)) {
            this.collision.identity();
        }
    }

    /** Shortest angle between two orientations, radians. */
    private static double stepBetween(final Quaterniondc from, final Quaterniondc to) {
        final Quaterniond delta = new Quaterniond(from).conjugate().mul(to).normalize();

        final double angle = delta.angle();

        if (!Double.isFinite(angle)) {
            return 0.0;
        }

        return angle > Math.PI ? 2.0 * Math.PI - angle : angle;
    }

    /** Clamps {@code candidate} so it is at most {@code maxStep} radians away from {@code from}. */
    private static void limitStep(final Quaterniondc from, final Quaterniond candidate,
                                  final double maxStep) {

        final double step = stepBetween(from, candidate);

        if (step <= maxStep || step <= 1.0e-9 || !(maxStep > 0.0)) {
            return;
        }

        candidate.set(new Quaterniond(from).slerp(candidate, maxStep / step).normalize());
    }

    /**
     * Decides whether the player is latched to a surface.
     *
     * <p>Three conditions, each ruling out a specific wrong behaviour: a real contact from the
     * probe rather than a direction inferred from a force; the RIDE pressing you into it rather
     * than gravity, which is why no test against world up is needed or wanted (an ordinary floor is
     * held by gravity so it cannot latch, while the inside of a drum passes whether it is beside
     * you, below you, or above you at the top of a loop); and hysteresis, so the boundary does not
     * flicker every tick.</p>
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
     * <p>Reads the RAW acceleration on purpose: a genuine hard stop is an enormous spike, and the
     * filter that protects everything else from noise would blunt exactly the signal this needs.
     * Requiring attachment first is what keeps it from becoming "players bounce off contraptions".</p>
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
        this.deviation = 0.0;
        this.alignment = 0.0;
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
        this.bodyTarget.set(WORLD_UP);
        this.apparent.set(0.0, -CfConfig.GRAVITY, 0.0);

        final double bodyAngle = this.orientation.angle();
        final double boxAngle = this.collision.angle();

        // Unwind rather than snap: stepping off a drum should let you stand up, not teleport you
        // upright. No clearance test on the way down - unwinding towards upright is the direction
        // that frees space, and refusing it could strand a body leaning with nothing to lean on.
        if (bodyAngle > 1.0e-4 || boxAngle > 1.0e-4) {
            final double alpha = CfConfig.smoothingAlpha(CfConfig.BODY_HALF_LIFE, 1.0 / 20.0);
            final double boxAlpha = CfConfig.smoothingAlpha(CfConfig.HITBOX_HALF_LIFE, 1.0 / 20.0);

            this.orientation.slerp(new Quaterniond(), alpha).normalize();
            this.collision.slerp(new Quaterniond(), boxAlpha).normalize();

            this.tilt = Math.max(0.0, this.tilt - 0.08);
            this.active = true;
        } else {
            this.orientation.identity();
            this.collision.identity();
            this.tilt = 0.0;
            this.active = false;
        }
    }

    /**
     * The orientation to hand Sable's collision path.
     *
     * <p>An orientation and nothing else. Sable builds the oriented box from the entity's
     * unrotated sizes and this quaternion, and it also snaps the box's yaw to the sub-level's grid
     * by itself - so the body is already square to the contraption, Sure Footing style, before this
     * contributes anything. All this adds is the lean.</p>
     *
     * @return the orientation, or null to leave the box upright
     */
    @Nullable
    public Quaterniondc collisionOrientation() {
        if (!this.active || !CfConfig.SPEC.isLoaded() || !CfConfig.HITBOX_ENABLED.get()) {
            return null;
        }

        if (!Double.isFinite(this.collision.w) || this.collision.angle() < 1.0e-3) {
            return null;
        }

        return new Quaterniond(this.collision).normalize();
    }

    /** Angle between a unit normal and world up, radians. */
    private static double angleOf(final Vector3dc normal) {
        return Math.acos(Math.min(1.0, Math.max(-1.0, normal.y())));
    }
}
