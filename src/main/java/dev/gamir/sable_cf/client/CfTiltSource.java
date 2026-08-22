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
 * <h2>Felt gravity, not the deck plane</h2>
 *
 * <p>Aligning the camera to the sub-level's plane is what makes a tilt feel like being glued to the
 * floor: the deck rolls 10 degrees, the view rolls 10 degrees, and your eyes conclude the world
 * moved rather than that you are standing on a slope. A real person on a listing deck keeps their
 * head near vertical, because the thing their balance tracks is gravity. So the target is apparent
 * gravity, and a gentle list, a banked turn and the inside of a fast drum all come out of that one
 * choice with no thresholds between them.</p>
 *
 * <h2>Three different signals, not one</h2>
 *
 * <p>Felt gravity alone is not enough, because three situations that produce a similar vector want
 * completely different camera behaviour, and treating them as one is what made this nauseating:</p>
 *
 * <ol>
 *   <li><b>A loop.</b> Felt-down sweeps a full circle, so a camera that follows it honestly rolls
 *       360 degrees - the single most nauseating thing a camera can do, and not what a human does
 *       either: you keep your head with your body and let the world go round you. Detected by the
 *       <i>horizontal</i> component of the sub-level's angular velocity, which is the useful
 *       discriminator - a banked turn yaws about a vertical axis and is left alone, while a flip or
 *       a loop rotates about a horizontal one. When detected, the target fades to a running average
 *       of felt-up whose window tracks the revolution period, and over a full revolution the
 *       centrifugal part of that average cancels and leaves real gravity.</li>
 *   <li><b>A sharp bank.</b> Should feel immediate. Driven by angular <i>acceleration</i>, not by
 *       the size of the centrifugal vector - see {@link #joltBoost}.</li>
 *   <li><b>Walking about on a spinning deck.</b> Should feel like nothing at all. Handled by
 *       excluding Coriolis from the target and by damping the spring harder, below.</li>
 * </ol>
 */
public final class CfTiltSource implements TiltSource {

    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    /**
     * Angular acceleration, rad/s^2, that counts as a decisive flick of the controls. Only sets
     * the scale of the response curve; the curve saturates, so this is not a threshold.
     */
    private static final double JOLT_REFERENCE = 6.0;

    /** Horizontal spin rate, rad/s, below which nothing is a loop - about one turn per seven seconds. */
    private static final double LOOP_RATE_LOW = 0.9;

    /** Horizontal spin rate, rad/s, above which it is unambiguously a loop - a flip every 2.5 s. */
    private static final double LOOP_RATE_HIGH = 2.5;

    /** Player speed, m/s, treated as "fully walking". Roughly a sprint. */
    private static final double WALK_REFERENCE = 4.3;

    private final TiltSpring spring = new TiltSpring();

    /** Low-pass of felt-up. During a loop this is what the camera aims at instead. */
    private final Vector3d averageUp = new Vector3d(0.0, 1.0, 0.0);

    private double loopCharge;
    private double walkCharge;

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

        final Vector3f rotation = new Vector3f();

        double response = CfConfig.CAMERA_RESPONSE.get();
        double damping = CfConfig.CAMERA_DAMPING.get();

        if (state.active) {
            // Coriolis is the only term produced by the player's own walking rather than by the
            // ride. Leaving it in meant every step changed where "down" was, which is why simple
            // walking on a turntable was nauseating even when nothing about the ride had changed.
            final Vector3d deckApparent = new Vector3d(state.apparent).sub(state.coriolis);

            final Vector3d up = new Vector3d(deckApparent).negate();

            if (up.lengthSquared() > 1.0e-9 && up.isFinite()) {
                up.normalize();

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

                    // Shortest arc from world up to felt up. Its axis is horizontal by
                    // construction, so there is no yaw in it to have to remove.
                    rotation.set(CfMath.log(new Quaternionf().rotationTo(WORLD_UP, target)));
                    reproject(rotation);

                    rotation.mul(CfConfig.CAMERA_AMOUNT.get().floatValue());

                    CfMath.clampAngle(rotation,
                            (float) Math.toRadians(CfConfig.CAMERA_MAX_TILT_DEG.get()));
                }
            }

            // A sharp manoeuvre stiffens the spring; walking calms it. Independent and both bounded,
            // so they cannot combine into something unstable.
            response *= 1.0 + joltBoost(state);
            response *= 1.0 - 0.4 * CfConfig.CAMERA_WALK_DAMPING.get() * this.updateWalk(state, dt);
            damping *= 1.0 + CfConfig.CAMERA_WALK_DAMPING.get() * this.walkCharge;
        } else {
            this.decay(dt);
        }

        // deltaTicks() is ACS's realtime delta, so the spring is framerate independent - which is
        // the whole reason it hands that out instead of partialTick.
        return this.spring.step(
                CfMath.exp(rotation), deltaTicks, (float) response, (float) damping);
    }

    /**
     * How much of a loop we are in, 0..1, and maintains the running average of felt-up.
     *
     * <p>The average's window is tied to the revolution period rather than fixed. That matters:
     * a fixed window either fails to cover a slow loop, in which case the average still swings and
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

        final double blend = 1.0 - Math.pow(2.0, -dt / halfLife);

        this.averageUp.lerp(feltUp, Math.min(1.0, Math.max(0.0, blend)));

        if (this.averageUp.lengthSquared() < 1.0e-6 || !this.averageUp.isFinite()) {
            this.averageUp.set(0.0, 1.0, 0.0);
        } else {
            this.averageUp.normalize();
        }

        final double raw = (flipRate - LOOP_RATE_LOW) / (LOOP_RATE_HIGH - LOOP_RATE_LOW);
        final double clamped = Math.min(1.0, Math.max(0.0, raw));
        final double target = clamped * clamped * (3.0 - 2.0 * clamped);

        // Charged rather than applied instantly, so one quick flick is not mistaken for a loop.
        final double chargeBlend = 1.0 - Math.pow(2.0, -dt / 0.3);

        this.loopCharge += (target - this.loopCharge) * Math.min(1.0, Math.max(0.0, chargeBlend));

        return this.loopCharge * CfConfig.CAMERA_LOOP_SUPPRESSION.get();
    }

    /**
     * How much the player is moving under their own power, 0..1, smoothed.
     *
     * <p>Smoothed because the raw value flickers every time you tap a key, and a spring whose
     * damping flickers is worse than one that is slightly wrong.</p>
     */
    private double updateWalk(final ForceState state, final double dt) {
        final double speed = Math.hypot(state.relativeVelocity.x, state.relativeVelocity.z);
        final double target = Math.min(1.0, speed / WALK_REFERENCE);

        final double blend = 1.0 - Math.pow(2.0, -dt / 0.25);

        this.walkCharge += (target - this.walkCharge) * Math.min(1.0, Math.max(0.0, blend));

        return this.walkCharge;
    }

    /**
     * Extra stiffness from a sharp manoeuvre, driven by the sub-level's angular acceleration.
     *
     * <p>This is the fix for "sometimes far too strong, sometimes barely there". The old trigger was
     * the magnitude of the centrifugal vector, which is {@code omega^2 * r} - it grows with radius
     * and with steady spin, neither of which is a manoeuvre. Standing at the rim of a steadily
     * rotating platform produced a large value while nothing was happening, and a genuinely sharp
     * flick near the axis produced a small one. Angular acceleration is the derivative of the spin
     * rate, so it is large exactly when the ride changes what it is doing and zero when it is doing
     * the same thing quickly - which is what "sharp" means.</p>
     *
     * <p>The curve is {@code x / (1 + x)}: monotonic, bounded by 1 and therefore by
     * {@code jolt_gain}, and with no knee to fall off. Predictable by construction rather than by
     * tuning - twice the flick gives more response, always, and it cannot spike.</p>
     */
    private static double joltBoost(final ForceState state) {
        final double gain = CfConfig.CAMERA_JOLT_GAIN.get();

        if (gain <= 0.0) {
            return 0.0;
        }

        final double magnitude = state.angularAcceleration.length();

        if (!Double.isFinite(magnitude) || magnitude <= 0.0) {
            return 0.0;
        }

        final double x = magnitude / JOLT_REFERENCE;

        return gain * (x / (1.0 + x));
    }

    private void decay(final double dt) {
        final double blend = Math.min(1.0, Math.max(0.0, 1.0 - Math.pow(2.0, -dt / 0.4)));

        this.loopCharge += (0.0 - this.loopCharge) * blend;
        this.walkCharge += (0.0 - this.walkCharge) * blend;
        this.averageUp.lerp(new Vector3d(0.0, 1.0, 0.0), blend);

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
