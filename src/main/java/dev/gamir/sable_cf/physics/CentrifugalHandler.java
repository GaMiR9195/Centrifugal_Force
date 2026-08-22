package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Turns the body frame's decisions into an actual change in the player's velocity.
 *
 * <h2>The one rule that keeps this honest</h2>
 *
 * <p>Minecraft has already applied gravity by the time this runs. So what is added here is</p>
 *
 * <pre>applied = wanted - gravity</pre>
 *
 * <p>and the immediate consequence is the correctness test this whole file is built around:
 * <b>standing on a stationary deck, {@code applied} is exactly zero</b>. No ride means no stick,
 * no stick means nothing is cancelled, the only load is gravity, and gravity minus gravity is
 * nothing. The mod cannot perturb ordinary play, not because the thresholds are set high but
 * because there is nothing for it to add.</p>
 *
 * <h2>One friction solve, not three</h2>
 *
 * <p>The previous version had a wall assist that cancelled tangential gravity, a separate slide
 * solver that considered only the fictitious forces, and a grip gate in front of both. Gravity was
 * therefore handled by the assist and invisible to the slide solver, which is why sliding felt
 * disconnected from the thing that was making you slide, and why turning grip up to 3 made the
 * sliding vanish rather than merely reduce - the gate closed completely before the solver ever
 * ran.</p>
 *
 * <p>Here every felt acceleration - ride, gravity and drag - goes into one load, and one Coulomb
 * solve decides how much of it the contact can hold:</p>
 *
 * <ol>
 *   <li><b>Stick cancels.</b> Being a wall-walker means the surface holds you the way a floor
 *       would, so {@code stick} of the tangential ride-and-gravity load simply does not reach you.
 *       At the top of a loop it also cancels {@code stick} of the outward pull, which is what makes
 *       hanging there possible at all.</li>
 *   <li><b>Friction holds what it can.</b> Budget is {@code grip * press * footing}, with a bonus
 *       while you are actively pushing into the surface. Under the budget nothing moves; over it,
 *       only the excess gets through. Not a gate - a threshold that the load is measured against
 *       and the remainder passed on, so grip 3 makes slides slower, never absent.</li>
 *   <li><b>Slides reach a terminal speed.</b> The excess is opposed by a viscous term rather than
 *       clipped, so a slip accelerates, settles, and stops accelerating. Clipping the acceleration
 *       - the old behaviour - produces a slide that keeps gaining speed as long as the load is over
 *       the budget, which is the "strange and nasty" part: it never converges to anything, so it
 *       never feels like sliding, only like losing control.</li>
 *   <li><b>Air resistance always gets through.</b> A fraction of tangential drag bypasses friction
 *       entirely, because the player's model is that air is the one thing that can unstick you.
 *       It is self-limiting: the bypass is paired with a damping term sized so the drift settles at
 *       {@code air_resistance.slide_max_speed} and stays there. On a "solnyshko" that is a slow,
 *       constant slide down the deck that you can walk against - present, controllable, and
 *       incapable of building into a launch.</li>
 * </ol>
 */
public final class CentrifugalHandler {

    /** m/s^2 to blocks/tick of velocity: a * dt gives m/s, times dt again gives blocks/tick. */
    private static final double ACCEL_TO_DELTA = CfConfig.TICK * CfConfig.TICK;

    /** Below this the stick is gone; above it, it was real. Used only to notice a sudden loss. */
    private static final double RELEASE_STICK_HIGH = 0.45;

    private static final double RELEASE_STICK_LOW = 0.12;

    /** How long to keep smoothing after a ride ends, ticks. */
    private static final int RELEASE_TICKS = 8;

    /**
     * The last state actually applied, for {@code /sable_cf status} and the overlay.
     *
     * <p>A snapshot rather than a second copy: it points at the live {@link ForceState} owned by
     * the body frame, so it cannot drift from what the physics used. Empty on a dedicated server,
     * where forces are the client's job and the honest answer is "nothing here".</p>
     */
    private static volatile ForceState lastState = new ForceState();

    public static ForceState lastState() {
        return lastState;
    }

    private int releaseTicks;

    @SubscribeEvent
    public void onPlayerTick(final PlayerTickEvent.Post event) {
        final Player player = event.getEntity();

        if (!player.isLocalPlayer()) {
            // Physics is applied by whoever owns the entity. Doing it for remote players would
            // fight their own updates, and doing it on the server would fight the client's.
            return;
        }

        if (!(player instanceof BodyFrameHolder holder)) {
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();

        if (frame == null) {
            return;
        }

        final ForceState state = frame.state();

        if (player.isSpectator() || player.getAbilities().flying || player.isPassenger()) {
            this.releaseTicks = 0;
            state.clear();
            return;
        }

        if (!frame.active()) {
            this.decayRelease(player, frame);
            return;
        }

        lastState = state;

        this.apply(player, frame, state);
    }

    private void apply(final Player player, final BodyFrame frame, final ForceState state) {
        final Vector3dc normal = frame.planeNormal();
        final double stick = CfConfig.clamp01(frame.stick());

        // ---- air resistance.
        //
        // Quadratic-ish in speed and soft-capped, so it is negligible at walking pace and becomes
        // the dominant nuisance at ride speed - which is what makes it the thing that decides
        // whether you can hold your footing on a fast wheel.
        final Vector3d drag = new Vector3d();

        if (CfConfig.AIR_ENABLED.get() && state.airSpeed > 1.0e-4) {
            final double magnitude = CfConfig.dragMagnitude(state.airSpeed)
                    * CfConfig.AIR_STRENGTH.get();

            drag.set(state.airVelocity).normalize(magnitude).negate();
        }

        if (!drag.isFinite()) {
            drag.zero();
        }

        state.drag.set(drag);

        // ---- the load, and how it splits about the floor.
        final Vector3d rideAndGravity = new Vector3d(state.apparent);
        final Vector3d load = new Vector3d(rideAndGravity).add(drag);

        final double pressTotal = -load.dot(normal);
        final double rgNormal = rideAndGravity.dot(normal);

        final Vector3d rgTangential = new Vector3d(rideAndGravity).fma(-rgNormal, normal);

        // ---- what the stick cancels.
        //
        // Tangential ride-and-gravity, because standing on a wall means gravity stops dragging you
        // along it; and the OUTWARD half of the normal load, because at the top of a loop that is
        // the only thing between you and the ground.
        final Vector3d assist = new Vector3d(rgTangential).mul(-stick);

        if (rgNormal > 0.0) {
            assist.fma(-stick * rgNormal, normal);
        }

        final Vector3d rest = new Vector3d(load).add(assist);

        // ---- friction.
        //
        // Adhesion clause: when the load is trying to pull you OFF the surface, the stick supplies
        // the press instead. Without it, hanging at the top of a loop would come with zero grip and
        // you would slide off sideways while still nominally attached - the worst of both.
        final double effectivePress = pressTotal >= 0.0 ? pressTotal : stick * -pressTotal;

        final double footing = CfConfig.footing(effectivePress);
        state.footing = footing;

        final double brace = brace(player);
        state.bracing = brace > 0.05;

        final double limit = CfConfig.GRIP_ENABLED.get()
                ? CfConfig.GRIP_STRENGTH.get() * effectivePress * footing
                        * (1.0 + CfConfig.GRIP_BRACE_BONUS.get() * brace)
                : 0.0;

        final double restNormal = rest.dot(normal);
        final Vector3d tangential = new Vector3d(rest).fma(-restNormal, normal);

        // Air's share, pulled out before the solve so friction can never take all of it away.
        final Vector3d dragTangential = new Vector3d(drag).fma(-drag.dot(normal), normal);
        final Vector3d bypass = new Vector3d(dragTangential)
                .mul(CfConfig.AIR_SLIDE.get() * stick);

        tangential.sub(bypass);

        final double tangentialMagnitude = tangential.length();
        state.tangentialLoad = tangentialMagnitude;

        double hold = tangentialMagnitude;

        if (tangentialMagnitude > limit) {
            hold = limit;

            // Only the excess survives. A proportional cut, not a clip: doubling grip halves the
            // slide instead of switching it off.
            tangential.mul((tangentialMagnitude - limit) / tangentialMagnitude);
            state.slipping = true;
        } else {
            tangential.zero();
            state.slipping = false;
        }

        state.hold = hold;
        state.gripped = limit > 0.0 && !state.slipping;

        // ---- viscous terms, so every slide has a terminal speed.
        final Vector3d relative = new Vector3d(state.relativeVelocity);
        final Vector3d relativeTangential = new Vector3d(relative)
                .fma(-relative.dot(normal), normal);

        if (state.slipping && CfConfig.GRIP_SLIDE_DAMPING.get() > 0.0) {
            tangential.fma(-CfConfig.GRIP_SLIDE_DAMPING.get(), relativeTangential);
        }

        final double slideCap = CfConfig.GRIP_SLIDE_CAP_G.get() * CfConfig.GRAVITY;

        if (tangential.length() > slideCap) {
            tangential.normalize(slideCap);
        }

        // Air's share is self-limiting: paired with damping sized so the drift settles at the
        // configured speed and then stops growing. This is the "it slides you off the solnyshko a
        // little" behaviour, and it converges by construction rather than by tuning.
        final double bypassMagnitude = bypass.length();

        if (bypassMagnitude > 1.0e-6) {
            final double terminal = Math.max(0.2, CfConfig.AIR_SLIDE_MAX_SPEED.get());

            bypass.fma(-bypassMagnitude / terminal, relativeTangential);
        }

        state.slip.set(tangential).add(bypass);

        // ---- put it back together.
        final Vector3d wanted = new Vector3d(normal).mul(restNormal)
                .add(tangential)
                .add(bypass);

        // Minecraft has already applied gravity this tick, so only the difference is ours to add.
        final Vector3d applied = new Vector3d(wanted).add(0.0, CfConfig.GRAVITY, 0.0);

        this.addRelease(player, frame, applied, relative);

        final double cap = CfConfig.MAX_ACCEL_G.get() * CfConfig.GRAVITY;

        if (applied.length() > cap) {
            applied.normalize(cap);
        }

        if (!applied.isFinite()) {
            return;
        }

        state.applied.set(applied);

        player.setDeltaMovement(player.getDeltaMovement().add(
                applied.x * ACCEL_TO_DELTA,
                applied.y * ACCEL_TO_DELTA,
                applied.z * ACCEL_TO_DELTA));
    }

    /**
     * Catches the moment a ride stops holding you and takes the edge off it.
     *
     * <p>Not a safety net for bad physics - the physics is right, and the physics says that letting
     * go of a wheel at 20 m/s launches you at 20 m/s. That is correct and it is also unplayable, so
     * the first few ticks of it are damped. Only the velocity <i>relative to the deck</i> is
     * touched, so being carried along by the contraption is preserved exactly; what gets removed is
     * the part that came from the ride flinging you.</p>
     */
    private void addRelease(final Player player, final BodyFrame frame,
                            final Vector3d applied, final Vector3dc relative) {

        if (!CfConfig.RELEASE_ENABLED.get()) {
            this.releaseTicks = 0;
            return;
        }

        if (frame.previousStick() > RELEASE_STICK_HIGH && frame.stick() < RELEASE_STICK_LOW) {
            this.releaseTicks = RELEASE_TICKS;
        }

        if (this.releaseTicks <= 0) {
            return;
        }

        this.releaseTicks--;

        final double speed = relative.length();

        if (speed < CfConfig.RELEASE_MIN_SPEED.get()) {
            this.releaseTicks = 0;
            return;
        }

        final double decel = Math.min(
                CfConfig.RELEASE_DECEL_G.get() * CfConfig.GRAVITY,
                (speed - CfConfig.RELEASE_MIN_SPEED.get()) / CfConfig.TICK);

        applied.fma(-decel / speed, relative);

        frame.state().released = true;
    }

    /** Runs the release damping out after the player has left the sub-level entirely. */
    private void decayRelease(final Player player, final BodyFrame frame) {
        frame.state().clear();

        if (this.releaseTicks <= 0) {
            return;
        }

        this.releaseTicks--;

        final Vec3 velocity = player.getDeltaMovement();
        final double speed = velocity.length() / CfConfig.TICK;

        if (speed < CfConfig.RELEASE_MIN_SPEED.get()) {
            this.releaseTicks = 0;
            return;
        }

        final double decel = Math.min(
                CfConfig.RELEASE_DECEL_G.get() * CfConfig.GRAVITY,
                (speed - CfConfig.RELEASE_MIN_SPEED.get()) / CfConfig.TICK);

        final double scale = decel * ACCEL_TO_DELTA / Math.max(1.0e-6, velocity.length());

        player.setDeltaMovement(velocity.subtract(velocity.scale(scale)));

        frame.state().released = true;
    }

    /**
     * How hard the player is pushing into the surface, 0..1.
     *
     * <p>Holding a movement key is a claim that you are actively planted, and it buys real grip.
     * That is a game-feel decision rather than a physical one, and it is the difference between a
     * wall you can walk on and a wall you can only be pinned to.</p>
     */
    private static double brace(final Player player) {
        final double forward = Math.abs(player.zza);
        final double strafe = Math.abs(player.xxa);

        return CfConfig.clamp01(Math.max(forward, strafe));
    }
}
