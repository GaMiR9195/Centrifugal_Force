package dev.gamir.sable_cf.client;

import com.playsi.aero_cam_sync.api.TiltContext;
import com.playsi.aero_cam_sync.api.TiltSource;
import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
import dev.gamir.sable_cf.physics.CfMath;
import dev.gamir.sable_cf.physics.ForceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Where the camera actually points.
 *
 * <h2>The target is the body, not the force</h2>
 *
 * <p>This used to aim straight at felt gravity. That sounds right - balance tracks gravity - but it
 * produces a camera that rolls the entire centrifugal angle on every bank while the body it belongs
 * to stands bolt upright. A great deal of motion, none of it corresponding to anything the
 * character is doing, and it heels hardest exactly when you are least pinned. That is the "why is
 * it leaning so far" problem, and no amount of damping fixes it, because the target itself was
 * wrong.</p>
 *
 * <p>So the target is the <b>body's</b> up direction, computed once in {@code BodyFrame} and shared
 * with the hitbox. Three things follow, none of them tuned:</p>
 *
 * <ul>
 *   <li>An ordinary deck - moving, accelerating, sloped, banking gently - rotates the body by
 *       nothing, because the body tilt is gated on the ride's share of the press. So the camera
 *       does nothing either.</li>
 *   <li>A drum that genuinely pins you rotates body and camera together, so standing on the wall
 *       looks like standing on the wall rather than like the world falling over.</li>
 *   <li>The view and the hitbox cannot disagree, because there is one orientation and both read
 *       it.</li>
 * </ul>
 *
 * <p>A small, explicit lean towards felt gravity is added on top. With the body as the target that
 * term is the only thing left that moves the view when you are not pinned, so it is what stops a
 * change of direction from going numb - which is the other half of what was wanted.</p>
 *
 * <h2>Gentle, but not numb</h2>
 *
 * <p>Two separate problems, and conflating them is why this was hard to tune:</p>
 *
 * <ul>
 *   <li><b>Tremble.</b> The pose arrives over the network, so the target is never perfectly still,
 *       and a camera that tracks it faithfully never stops twitching. Fixed by filtering the
 *       <i>target</i> and applying a dead band to it - not by slowing the camera down, which would
 *       have cost the response as well.</li>
 *   <li><b>Response.</b> Driven by how fast the target is swinging, rather than by the magnitude of
 *       the centrifugal vector. Magnitude is {@code omega^2 * r}: it grows with radius and with
 *       perfectly steady spin, so it reads large when nothing is happening and small for a genuine
 *       flick near the axis, which is exactly the "sometimes too much, sometimes nothing"
 *       instability.</li>
 * </ul>
 *
 * <h2>Three different signals, not one</h2>
 *
 * <ol>
 *   <li><b>A loop.</b> The body's up sweeps a full circle, so a camera that follows it honestly
 *       rolls 360 degrees - the single most nauseating thing a camera can do, and not what a human
 *       does either. Detected by the <i>horizontal</i> component of the sub-level's angular
 *       velocity: a banked turn yaws about a vertical axis and is left alone, a flip rotates about
 *       a horizontal one. When detected, the target fades to a running average whose window tracks
 *       the revolution period.</li>
 *   <li><b>A sharp bank.</b> Should feel immediate. See {@link #joltBoost}.</li>
 *   <li><b>Walking about on a spinning deck.</b> Should feel like nothing at all. Handled by
 *       excluding Coriolis and by damping the spring harder.</li>
 * </ol>
 */
public final class CfTiltSource implements TiltSource {

    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    /**
     * Rate at which the target swings, rad/s, that counts as a decisive change of direction. Only
     * sets the scale of the response curve; the curve saturates, so this is not a threshold.
     */
    private static final double TURN_REFERENCE = 1.2;

    /** Angular acceleration, rad/s^2, that counts as a decisive flick of the controls. */
    private static final double JOLT_REFERENCE = 8.0;

    /** Horizontal spin rate, rad/s, below which nothing is a loop - about one turn per seven seconds. */
    private static final double LOOP_RATE_LOW = 0.9;

    /** Horizontal spin rate, rad/s, above which it is unambiguously a loop - a flip every 2.5 s. */
    private static final double LOOP_RATE_HIGH = 2.5;

    /** Speed across the deck, m/s, treated as "fully walking". Roughly a sprint. */
    private static final double WALK_REFERENCE = 4.3;

    private final TiltSpring spring = new TiltSpring();

    /** Low-pass of the target. During a loop this is what the camera aims at instead. */
    private final Vector3d averageUp = new Vector3d(0.0, 1.0, 0.0);

    /** The target last frame, for measuring how fast it is swinging. */
    private final Vector3d previousUp = new Vector3d(0.0, 1.0, 0.0);

    /** The filtered rotation, before the dead band. */
    private final Vector3f smoothTarget = new Vector3f();

    /** The rotation the spring actually chases, after the dead band. */
    private final Vector3f heldTarget = new Vector3f();

    private double loopCharge;
    private double walkCharge;
    private double turnRate;

    @Override
    public boolean appliesTo(final TiltContext context) {
        if (!CfConfig.SPEC.isLoaded() || !CfConfig.CAMERA_ENABLED.get()) {
            return false;
        }

        // Third person is ACS's business; a rolled third-person camera is disorienting in a way
        // that has nothing to do with what we are modelling.
        if (!context.firstPerson()) {
            return false;
        }

        // Keep claiming the frame while the spring still has tilt to unwind. Handing it back
        // mid-lean would snap the view level in one frame, which is the one camera artefact
        // guaranteed to be noticed.
        return CentrifugalHandler.STATE.active || this.spring.hasResidual();
    }

    @Override
    public Quaternionf tilt(final TiltContext context) {
        // Never ask ACS for its state from in here - AcsHandle#state() polls the sources, and this
        // is one of them. It is a straight infinite recursion. Everything needed is on the context.
        final ForceState state = CentrifugalHandler.STATE;

        final float deltaTicks = Math.min(Math.max(context.deltaTicks(), 0.0f), 4.0f);
        final double dt = deltaTicks / 20.0;

        final Vector3f raw = new Vector3f();

        double response = CfConfig.CAMERA_RESPONSE.get();
        double damping = CfConfig.CAMERA_DAMPING.get();

        if (state.active) {
            final Vector3d up = new Vector3d(state.bodyUp);

            if (up.lengthSquared() < 1.0e-9 || !up.isFinite()) {
                up.set(0.0, 1.0, 0.0);
            }

            up.normalize();

            // The small deliberate lean towards felt gravity. Coriolis is excluded because it is
            // the only term produced by the player's own walking rather than by the ride, and
            // leaving it in meant every step changed where down was.
            final Vector3d feltUp = new Vector3d(state.apparent).sub(state.coriolis).negate();

            if (feltUp.lengthSquared() > 1.0e-9 && feltUp.isFinite()) {
                feltUp.normalize();
                up.lerp(feltUp, CfConfig.CAMERA_FELT_LEAN);

                if (up.lengthSquared() < 1.0e-9 || !up.isFinite()) {
                    up.set(0.0, 1.0, 0.0);
                } else {
                    up.normalize();
                }
            }

            this.updateTurnRate(up, dt);

            final double loopFactor = this.updateLoop(state, up, dt);

            if (loopFactor > 1.0e-3) {
                up.lerp(this.averageUp, loopFactor);

                if (up.lengthSquared() < 1.0e-9) {
                    up.set(0.0, 1.0, 0.0);
                }

                up.normalize();
            }

            final Vector3f target = new Vector3f((float) up.x, (float) up.y, (float) up.z);

            final Vector3f deck = new Vector3f(
                    (float) state.normal.x, (float) state.normal.y, (float) state.normal.z);

            if (deck.lengthSquared() > 1.0e-9f) {
                target.lerp(deck.normalize(), CfConfig.CAMERA_DECK_LEAN.get().floatValue());
            }

            if (target.lengthSquared() > 1.0e-9f) {
                target.normalize();

                // Shortest arc from world up to the target. Its axis is horizontal by
                // construction, so there is no yaw in it to have to remove.
                raw.set(CfMath.log(new Quaternionf().rotationTo(WORLD_UP, target)));
                reproject(raw);

                raw.mul(CfConfig.CAMERA_AMOUNT.get().floatValue());

                CfMath.clampAngle(raw,
                        (float) Math.toRadians(CfConfig.CAMERA_MAX_TILT_DEG.get()));
            }

            // A change of direction stiffens the spring; walking calms it. Independent and both
            // bounded, so they cannot combine into something unstable.
            response *= 1.0 + this.joltBoost(state);
            response *= 1.0 - 0.4 * CfConfig.CAMERA_WALK_DAMPING.get() * this.updateWalk(state, dt);
            damping *= 1.0 + CfConfig.CAMERA_WALK_DAMPING.get() * this.walkCharge;
        } else {
            this.decay(dt);
        }

        // Filter the target, not the camera. Filtering the output would make the camera late as
        // well as smooth; filtering the goal lets the spring keep chasing hard, it just is not
        // handed a jittering thing to chase.
        final float blend = (float) CfConfig.smoothingAlpha(CfConfig.CAMERA_SMOOTHING.get(), dt);

        this.smoothTarget.lerp(raw, Math.min(1.0f, Math.max(0.0f, blend)));

        if (!this.smoothTarget.isFinite()) {
            this.smoothTarget.zero();
        }

        this.applyDeadBand();

        // deltaTicks() is ACS's realtime delta, so the spring is framerate independent - which is
        // the whole reason it hands that out instead of partialTick.
        return this.spring.step(
                CfMath.exp(this.heldTarget), deltaTicks, (float) response, (float) damping);
    }

    /**
     * Slop, not a threshold.
     *
     * <p>Motion smaller than the dead band does not move the camera at all, which is what removes
     * the micro-tremble. Past the dead band the target follows continuously, offset by the dead band
     * - so there is no step, and therefore no stair-stepping, which is what a hard threshold would
     * have traded the tremble for.</p>
     */
    private void applyDeadBand() {
        final float band = (float) Math.toRadians(CfConfig.CAMERA_DEADBAND_DEG.get());

        if (band <= 0.0f) {
            this.heldTarget.set(this.smoothTarget);
            return;
        }

        final Vector3f error = new Vector3f(this.smoothTarget).sub(this.heldTarget);
        final float magnitude = error.length();

        if (magnitude <= band || !Float.isFinite(magnitude)) {
            return;
        }

        this.heldTarget.add(error.mul((magnitude - band) / magnitude));

        if (!this.heldTarget.isFinite()) {
            this.heldTarget.zero();
        }
    }

    /**
     * How fast the target is swinging, rad/s, smoothed.
     *
     * <p>The honest measure of "the direction of the force is changing", which is what a rider feels
     * as a change of direction. Zero for a steady spin however violent, and large for a genuine
     * change however small the ride.</p>
     */
    private void updateTurnRate(final Vector3d up, final double dt) {
        if (dt <= 0.0) {
            return;
        }

        final double dot = Math.min(1.0, Math.max(-1.0, up.dot(this.previousUp)));
        final double instant = Math.acos(dot) / dt;

        this.previousUp.set(up);

        if (!Double.isFinite(instant)) {
            return;
        }

        // Smoothed, because one noisy frame should not spike the response the whole point of which
        // is to be predictable.
        final double blend = CfConfig.smoothingAlpha(0.09, dt);

        this.turnRate += (instant - this.turnRate) * blend;
    }

    /**
     * How much of a loop we are in, 0..1, and maintains the running average of the target.
     *
     * <p>The average's window is tied to the revolution period rather than fixed. That matters: a
     * fixed window either fails to cover a slow loop, in which case the average still swings and
     * the camera still rolls, or over-smooths a fast one, in which case the camera stops responding
     * to anything. Half a revolution is enough for the swinging part to cancel.</p>
     */
    private double updateLoop(final ForceState state, final Vector3d feltUp, final double dt) {
        // Horizontal component only. Rotation about a vertical axis is a turn, and a turn should
        // still lean; rotation about a horizontal axis is a flip, and that is what has to be
        // suppressed. This one projection is the whole discriminator.
        final double flipRate = Math.hypot(state.omega.x, state.omega.z);

        final double period = flipRate > 1.0e-3 ? (2.0 * Math.PI / flipRate) : Double.MAX_VALUE;
        final double halfLife = Math.min(2.0, Math.max(0.25, 0.5 * Math.min(period, 4.0)));

        final double blend = CfConfig.smoothingAlpha(halfLife, dt);

        this.averageUp.lerp(feltUp, Math.min(1.0, Math.max(0.0, blend)));

        if (this.averageUp.lengthSquared() < 1.0e-6 || !this.averageUp.isFinite()) {
            this.averageUp.set(0.0, 1.0, 0.0);
        } else {
            this.averageUp.normalize();
        }

        final double target = CfConfig.smoothstep(flipRate, LOOP_RATE_LOW, LOOP_RATE_HIGH);

        // Charged rather than applied instantly, so one quick flick is not mistaken for a loop.
        final double chargeBlend = CfConfig.smoothingAlpha(0.3, dt);

        this.loopCharge += (target - this.loopCharge) * Math.min(1.0, Math.max(0.0, chargeBlend));

        return this.loopCharge * CfConfig.CAMERA_LOOP_SUPPRESSION.get();
    }

    /**
     * How much the player is moving across the deck, 0..1, smoothed.
     *
     * <p>Reads the measured deck-relative velocity rather than {@code deltaMovement}, so being
     * dragged across a drum counts as motion for the purpose of calming the camera, which is
     * exactly when you want it calmed.</p>
     */
    private double updateWalk(final ForceState state, final double dt) {
        final double speed = state.deckRelativeVelocity.length();
        final double target = Math.min(1.0, speed / WALK_REFERENCE);

        final double blend = CfConfig.smoothingAlpha(0.25, dt);

        this.walkCharge += (target - this.walkCharge) * Math.min(1.0, Math.max(0.0, blend));

        return this.walkCharge;
    }

    /**
     * Extra stiffness from a change of direction.
     *
     * <p>Primarily the rate at which the target is swinging, with a smaller contribution from the
     * sub-level's angular acceleration so that a sharp flick of the controls registers even before
     * the force has finished moving. Both are derivatives: they are large exactly when the ride
     * changes what it is doing, and zero when it is doing the same thing quickly - which is what
     * "sharp" means.</p>
     *
     * <p>The curve is {@code x / (1 + x)}: monotonic, bounded by 1 and therefore by
     * {@code jolt_gain}, and with no knee to fall off. Predictable by construction rather than by
     * tuning - twice the swing gives more response, always, and it cannot spike.</p>
     */
    private double joltBoost(final ForceState state) {
        final double gain = CfConfig.CAMERA_JOLT_GAIN.get();

        if (gain <= 0.0) {
            return 0.0;
        }

        final double swing = this.turnRate / TURN_REFERENCE;
        final double flick = state.angularAcceleration.length() / JOLT_REFERENCE;

        final double x = swing + 0.5 * flick;

        if (!Double.isFinite(x) || x <= 0.0) {
            return 0.0;
        }

        return gain * (x / (1.0 + x));
    }

    private void decay(final double dt) {
        final double blend = CfConfig.smoothingAlpha(0.4, dt);

        this.loopCharge += (0.0 - this.loopCharge) * blend;
        this.walkCharge += (0.0 - this.walkCharge) * blend;
        this.turnRate += (0.0 - this.turnRate) * blend;
        this.averageUp.lerp(new Vector3d(0.0, 1.0, 0.0), blend);
        this.previousUp.set(0.0, 1.0, 0.0);

        if (this.averageUp.lengthSquared() < 1.0e-6 || !this.averageUp.isFinite()) {
            this.averageUp.set(0.0, 1.0, 0.0);
        } else {
            this.averageUp.normalize();
        }
    }

    /**
     * Rewrites the tilt in the player's own frame: full roll, damped pitch, no yaw.
     *
     * <p>Rotation vectors are what makes this a three-line operation - you can project and scale
     * them componentwise, which is true of neither quaternions nor Euler angles.</p>
     */
    private static void reproject(final Vector3f rotation) {
        final LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) {
            return;
        }

        final float yaw = (float) Math.toRadians(player.getYRot());

        // Minecraft yaw 0 looks along +Z.
        final Vector3f forward = new Vector3f(-(float) Math.sin(yaw), 0.0f, (float) Math.cos(yaw));
        final Vector3f right = new Vector3f(forward).cross(WORLD_UP);

        if (right.lengthSquared() < 1.0e-9f) {
            return;
        }

        right.normalize();

        // Rotation about your forward axis is roll; about your right axis is pitch. Read both
        // before overwriting.
        final float roll = rotation.dot(forward);
        final float pitch = rotation.dot(right) * CfConfig.CAMERA_PITCH_RESPONSE.get().floatValue();

        rotation.set(forward).mul(roll).add(new Vector3f(right).mul(pitch));
    }
}
