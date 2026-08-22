package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One player's relationship with the surface they are riding: which way is down, how much of a
 * wall-walker they currently are, and what orientation their body and their collision box should
 * be at.
 *
 * <h2>The model</h2>
 *
 * <p>Centrifugal force is treated as <b>wall walking that has to be earned</b>. Spin a drum fast
 * enough and its wall becomes a floor: you stand on it with your body and with your hitbox, you
 * walk around on it, and the only thing still trying to pull you off is air resistance. Stop
 * spinning and it is a wall again. Everything below is in service of that one sentence.</p>
 *
 * <p>The single number that expresses it is {@link #stick()}, 0 to 1. It is earned by
 * {@code ridePress} - how hard the <i>ride alone</i>, gravity excluded, presses you into the
 * surface you are touching - and it buys three things at once:</p>
 *
 * <ul>
 *   <li>how far the body and the hitbox turn to stand on that surface,</li>
 *   <li>how much of the apparent load is simply cancelled instead of having to be held by
 *       friction,</li>
 *   <li>how strongly the camera adopts the new down.</li>
 * </ul>
 *
 * <p>Buying all three with the same number is what stops the three from disagreeing. The previous
 * version had a tilt ramp, an attach threshold, a wall-assist threshold and a camera ramp, each
 * with its own thresholds; a player crossing four thresholds at four different moments feels every
 * one of them as a separate lurch.</p>
 *
 * <h2>The 360 loop</h2>
 *
 * <p>The loop was reported as the case where the hitbox did not rotate at all, and the cause was
 * the old gate rather than the force: the tilt was multiplied by
 * {@code smoothstep(1 - feltUp.y, 0.04, 0.35)}, and on a big slow wheel felt-down stays within a
 * degree of world down, so that factor was <b>exactly zero</b> for the whole ride. The mod was
 * measuring how tilted the force was, when what matters is which surface you are touching and how
 * hard.</p>
 *
 * <p>Now the loop is simply the case where the committed plane goes all the way round. Nothing
 * special is done for it, and three separate things had to stop fighting it:
 * {@link GravityPlane} follows the deck continuously through the top rather than picking a new
 * best normal every tick; {@code WALL_LOOP_ASSIST} keeps the stick alive across the top where the
 * press briefly goes negative; and {@code SubLevelEntityCollisionMixin} centres the collision
 * pivot, which is what makes a full turn cost zero net displacement and therefore be safe to
 * follow.</p>
 */
public final class BodyFrame {

    private static final double MIN_ANGLE = 1.0e-3;

    private final FrameKinematics kinematics = new FrameKinematics();
    private final ContactProbe probe = new ContactProbe();
    private final GravityPlane plane = new GravityPlane();
    private final ForceState state = new ForceState();

    /** Smoothed body orientation. Drives the probe, the forces and the camera. */
    private final Quaterniond body = new Quaterniond();

    /** Smoothed orientation actually handed to Sable's collision. Lags the body by design. */
    private final Quaterniond hitbox = new Quaterniond();

    @Nullable
    private Quaterniondc collisionOrientation;

    @Nullable
    private SubLevel subLevel;

    private double stick;
    private double stickPrevious;
    private boolean active;

    // Scratch. Reused because tick() runs for every player on both sides.
    private final Vector3d position = new Vector3d();
    private final Vector3d worldVelocity = new Vector3d();
    private final Vector3d relative = new Vector3d();
    private final Vector3d ride = new Vector3d();
    private final Vector3d apparent = new Vector3d();
    private final Vector3d scratch = new Vector3d();

    // ------------------------------------------------------------------ per tick

    public void tick(final Entity entity) {
        final SubLevel tracking = SableAccess.tracking(entity);

        if (tracking == null) {
            this.relax();
            return;
        }

        this.subLevel = tracking;

        if (!this.kinematics.sample(tracking)) {
            this.relax();
            return;
        }

        final Level level = entity.level();

        if (level == null) {
            this.relax();
            return;
        }

        this.state.clear();
        this.state.active = true;

        final double height = Math.max(0.1, entity.getBbHeight());

        this.position.set(entity.getX(), entity.getY() + height * 0.5, entity.getZ());

        // ---- velocities.
        //
        // The player's own delta plus what Sable is carrying them with. Only the sum is a real
        // world velocity, and only a real world velocity can be differenced against the deck to
        // get "how fast am I sliding" or against the air to get "how hard is the wind".
        final Vec3 own = entity.getDeltaMovement().scale(1.0 / CfConfig.TICK);
        final Vec3 inherited = SableAccess.inheritedVelocity(entity);

        this.worldVelocity.set(own.x + inherited.x, own.y + inherited.y, own.z + inherited.z);

        final Vector3d deckVelocity = this.kinematics.velocityAt(this.position, new Vector3d());

        this.relative.set(this.worldVelocity).sub(deckVelocity);

        final double maxRelative = CfConfig.MAX_DECK_RELATIVE;

        if (this.relative.length() > maxRelative) {
            this.relative.normalize(maxRelative);
        }

        final Vec3 wind = SableAccess.wind(level, new Vec3(this.position.x, this.position.y, this.position.z));

        this.state.airVelocity.set(
                this.worldVelocity.x - wind.x,
                this.worldVelocity.y - wind.y,
                this.worldVelocity.z - wind.z);

        this.state.airSpeed = this.state.airVelocity.length();

        this.state.deckVelocity.set(deckVelocity);
        this.state.deckTranslation.set(this.kinematics.pivotVelocity());
        this.state.relativeVelocity.set(this.relative);

        // ---- the ride, as felt.
        //
        // Felt acceleration is minus the frame's acceleration, term by term. Kept as three separate
        // vectors because they mean different things to the rest of the mod and because the overlay
        // is the main debugging tool for this mod - a single blended vector would be unreadable.
        this.kinematics.centripetalAt(this.position, this.scratch);
        this.state.centrifugal.set(this.scratch).negate();

        this.kinematics.eulerAt(this.position, this.scratch);
        this.state.euler.set(this.scratch).negate();

        this.state.linear.set(this.kinematics.linearAcceleration()).negate();

        // Coriolis: -2 omega x v_rel. Zero unless you are moving ACROSS the deck, which is exactly
        // when it should exist - it is the force that curves your path as you walk in towards the
        // axis, and leaving it out is why walking in used to feel like being shoved sideways by
        // nothing.
        this.kinematics.omega().cross(this.relative, this.scratch);
        this.state.coriolis.set(this.scratch).mul(-2.0 * CfConfig.CORIOLIS_STRENGTH.get());

        this.ride.set(this.state.centrifugal)
                .add(this.state.euler)
                .add(this.state.linear)
                .add(this.state.coriolis);

        final double maxRide = CfConfig.MAX_ACCEL_G.get() * CfConfig.GRAVITY;

        if (this.ride.length() > maxRide) {
            this.ride.normalize(maxRide);
        }

        this.ride.mul(CfConfig.CENTRIFUGAL_ENABLED.get() ? CfConfig.CENTRIFUGAL_STRENGTH.get() : 0.0);

        // Apparent = ride + gravity. Drag is deliberately NOT in here: the plane choice should be
        // about which surface is holding you up, and drag is a small, noisy, direction-changing
        // term that would make that decision flicker. Drag gets its say in the friction solve,
        // which is where the player asked for it to matter.
        this.apparent.set(this.ride).add(0.0, -CfConfig.GRAVITY, 0.0);

        this.state.apparent.set(this.apparent);
        this.state.omega.set(this.kinematics.omega());
        this.state.angularAcceleration.set(this.kinematics.alpha());

        final double spinGate = CfConfig.spinGate(this.kinematics.spin());
        this.state.spinGate = spinGate;

        // ---- what am I touching, and which of it is the floor.
        final int contacts = this.probe.probe(entity, tracking, this.body);
        this.state.contactCount = contacts;

        this.plane.update(this.probe, this.apparent, CfConfig.PLANE_ENABLED.get());

        this.state.planeIndex = this.plane.index();
        this.state.challengerIndex = this.plane.challenger();
        this.state.challengerTicks = this.plane.challengerTicks();
        this.state.normal.set(this.plane.normal());
        this.state.press = this.plane.support();

        // ---- earn the stick.
        //
        // Ride-only press, gravity excluded. This is the whole reason an ordinary lift, ramp or
        // sailing airship cannot turn into a wall-walking surface: they produce no ride press, so
        // they buy no stick, so nothing below them does anything.
        final double ridePress = this.plane.committed()
                ? -this.ride.dot(this.plane.normal())
                : 0.0;

        this.state.ridePress = ridePress;

        this.stickPrevious = this.stick;
        this.stick = this.plane.committed() ? CfConfig.stick(ridePress, spinGate) : 0.0;

        if (!CfConfig.WALL_ENABLED.get()) {
            this.stick = 0.0;
        }

        this.state.stick = this.stick;
        this.state.footing = CfConfig.footing(Math.max(0.0, this.plane.support()));
        this.state.wallRide = this.stick > 0.5 && this.plane.normal().y() < CfConfig.WALL_COSINE;

        this.active = true;

        // ---- posture.
        this.updateBody(entity, tracking);
    }

    /**
     * Steps the body and the hitbox towards the posture the current stick asks for.
     *
     * <p>Two orientations rather than one, and the hitbox is the slower of the two. The body is
     * what the probe and the forces reason with, so it should track the truth closely; the hitbox
     * is what the world will collide, so it should never move faster than the world can be checked
     * for room. They converge whenever nothing is in the way, which is almost always.</p>
     */
    private void updateBody(final Entity entity, final SubLevel tracking) {
        final double dt = CfConfig.TICK;

        // Target: upright, rotated towards the committed plane by however much stick is owned. The
        // interpolation is on the ORIENTATION, not on the normal - a half-turned body is a real
        // posture, whereas a half-chosen floor is a direction no surface points in.
        final Quaterniond target = new Quaterniond();

        if (this.stick > 0.0 && this.plane.committed()) {
            target.slerp(this.plane.rotation(), Math.min(1.0, this.stick)).normalize();
        }

        CfMath.approach(this.body, target,
                CfConfig.smoothingAlpha(CfConfig.PLANE_HALF_LIFE.get(), dt),
                Math.toRadians(CfConfig.PLANE_SLEW_DEG_PER_S.get()) * dt);

        this.state.bodyAngleDeg = Math.toDegrees(CfMath.angleOf(this.body));

        if (!CfConfig.HITBOX_ENABLED.get()) {
            this.releaseHitbox(dt);
            return;
        }

        // The hitbox may be asked for less than the body - some players want the visual without the
        // collision - and is capped separately.
        final Quaterniond hitboxTarget = new Quaterniond()
                .slerp(this.body, CfConfig.clamp01(CfConfig.HITBOX_AMOUNT.get()))
                .normalize();

        final double maxAngle = Math.toRadians(Math.max(0.0, CfConfig.HITBOX_MAX_DEG.get()));

        if (CfMath.angleOf(hitboxTarget) > maxAngle) {
            final Vector3d capped = CfMath.log(hitboxTarget, new Vector3d());

            CfMath.clampAngle(capped, maxAngle);
            CfMath.exp(capped, hitboxTarget);
        }

        final double alpha = CfConfig.smoothingAlpha(CfConfig.HITBOX_HALF_LIFE.get(), dt);
        final double slew = Math.toRadians(CfConfig.HITBOX_SLEW_DEG_PER_S.get()) * dt;

        // Try the full step; if the posture would not fit, try smaller ones before giving up.
        //
        // Backing off to a SMALLER step rather than to upright is the fix for the reported
        // "leaning, refusing, leaning" shake. The old code failed the clearance test and reverted,
        // which put the body back where it had just been refused from, so the next tick tried the
        // same move again - a loop that oscillates at tick rate by construction. Holding the last
        // posture that did fit is stable: worst case the hitbox simply stops where the geometry
        // stops it.
        boolean fitted = false;

        for (final double scale : new double[]{1.0, 0.5, 0.25}) {
            final Quaterniond candidate = new Quaterniond(this.hitbox);

            CfMath.approach(candidate, hitboxTarget, alpha * scale, slew * scale);

            if (Clearance.fits(entity, tracking, candidate)) {
                this.hitbox.set(candidate);
                fitted = true;
                break;
            }
        }

        this.state.clearanceBlocked = !fitted;

        this.publishHitbox();
    }

    /** Nothing to ride: unwind the posture and let go of the floor. */
    private void relax() {
        final double dt = CfConfig.TICK;

        this.active = false;
        this.subLevel = null;
        this.stickPrevious = this.stick;
        this.stick = 0.0;

        this.kinematics.reset();
        this.probe.clear();
        this.plane.release();

        this.state.clear();

        final Quaterniond upright = new Quaterniond();

        CfMath.approach(this.body, upright,
                CfConfig.smoothingAlpha(CfConfig.PLANE_HALF_LIFE.get(), dt),
                Math.toRadians(CfConfig.PLANE_SLEW_DEG_PER_S.get()) * dt);

        if (CfMath.angleOf(this.body) < MIN_ANGLE) {
            this.body.identity();
        }

        this.state.bodyAngleDeg = Math.toDegrees(CfMath.angleOf(this.body));

        this.releaseHitbox(dt);
    }

    /** Unwinds the collision orientation towards upright and drops it entirely once it is there. */
    private void releaseHitbox(final double dt) {
        CfMath.approach(this.hitbox, new Quaterniond(),
                CfConfig.smoothingAlpha(CfConfig.HITBOX_HALF_LIFE.get(), dt),
                Math.toRadians(CfConfig.HITBOX_SLEW_DEG_PER_S.get()) * dt);

        this.publishHitbox();
    }

    /**
     * Publishes the collision orientation, or null when it is indistinguishable from upright.
     *
     * <p>Null rather than identity on purpose. Sable only consults
     * {@code getCustomEntityOrientation} when it is going to do something with the answer, and
     * returning identity sends it down a custom path to compute a delta that is provably the same
     * as the one its default path already computes - for every player on every deck, every frame.
     * Null is both cheaper and more honest.</p>
     */
    private void publishHitbox() {
        final double angle = CfMath.angleOf(this.hitbox);

        if (!Double.isFinite(this.hitbox.w) || angle < MIN_ANGLE) {
            this.hitbox.identity();
            this.collisionOrientation = null;
            this.state.hitboxAngleDeg = 0.0;
            return;
        }

        this.collisionOrientation = new Quaterniond(this.hitbox).normalize();
        this.state.hitboxAngleDeg = Math.toDegrees(angle);
    }

    // ------------------------------------------------------------------ readers

    /** Orientation for Sable's oriented collision box, or null for "upright, do nothing". */
    @Nullable
    public Quaterniondc collisionOrientation() {
        return this.collisionOrientation;
    }

    /** Smoothed body orientation. Always non-null; identity means upright. */
    public Quaterniondc bodyOrientation() {
        return this.body;
    }

    /** 0..1, how much of a wall-walker the player currently is. */
    public double stick() {
        return this.stick;
    }

    /** Last tick's stick, so a sudden loss of ride can be detected and smoothed. */
    public double previousStick() {
        return this.stickPrevious;
    }

    public boolean active() {
        return this.active;
    }

    public ForceState state() {
        return this.state;
    }

    public FrameKinematics kinematics() {
        return this.kinematics;
    }

    public GravityPlane plane() {
        return this.plane;
    }

    public ContactProbe contacts() {
        return this.probe;
    }

    @Nullable
    public SubLevel subLevel() {
        return this.subLevel;
    }

    /** World unit normal of the committed plane; world up when there is none. */
    public Vector3dc planeNormal() {
        return this.plane.normal();
    }
}
