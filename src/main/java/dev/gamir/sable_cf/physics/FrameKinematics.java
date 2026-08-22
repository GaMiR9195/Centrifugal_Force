package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The sub-level's motion, as angular velocity and angular acceleration - and the fictitious
 * acceleration at a point built <b>analytically</b> from them.
 *
 * <h2>Why this replaced differencing the pose twice</h2>
 *
 * <p>The previous version sampled Sable's velocity field at the player's local point on two ticks
 * and differenced it. That is the obvious thing to do and it has three problems, all of which the
 * player could see:</p>
 *
 * <ol>
 *   <li><b>It lags, and lag on a rotating vector is a direction error.</b> Sable's velocity is
 *       itself a backward difference of the pose, so it is centred half a tick in the past;
 *       differencing two of those centres the acceleration a full tick back. At 2.5 rad/s a tick is
 *       7 degrees, so the force pointed 7 degrees off to the side of where it should - visible on
 *       the debug arrows, and felt as being pushed slightly the wrong way while walking in.</li>
 *   <li><b>Filtering it made that worse.</b> A low-pass on a vector that is rotating does not
 *       average it, it drags it backwards, so the smoothing that was there to fight noise was
 *       adding several more degrees of the same error.</li>
 *   <li><b>A second difference of a networked pose is mostly noise.</b> The client sees an
 *       interpolated pose, so the second derivative picks up the interpolator's own seams. That is
 *       where the phantom triggers on ordinary, non-rotating sub-levels came from.</li>
 * </ol>
 *
 * <p>All three are artefacts of the method, not of the problem. A rigid body's acceleration field
 * is exactly</p>
 *
 * <pre>a(r) = a_pivot + alpha x r + omega x (omega x r)</pre>
 *
 * <p>so if you know omega you know the whole field in closed form. Omega needs <i>one</i>
 * differentiation of the pose instead of two, which is an order of magnitude less noise; and the
 * centripetal term is rebuilt from the player's CURRENT radius every tick, so it points exactly at
 * the axis by construction. Filtering omega costs nothing in direction, because omega on a steady
 * spin is constant - the thing that was rotating was the acceleration, not the angular velocity.</p>
 *
 * <p>The residual half-tick of lag in omega itself is removed by extrapolating it forward with
 * alpha ({@code centrifugal_force.lead_ticks}), which is the honest way to cancel a known,
 * quantified delay rather than a fudge factor.</p>
 *
 * <h2>The three terms are kept separate on purpose</h2>
 *
 * <p>Only the rotational ones are wanted. A lift accelerating upwards produces {@code a_pivot} and
 * nothing else, so keeping it separate lets it be dead-zoned into oblivion without touching the
 * ride physics - which is the structural reason an ordinary moving contraption cannot trigger this
 * mod, as opposed to a threshold that merely makes it unlikely.</p>
 */
public final class FrameKinematics {

    /** Beyond this the pose jumped; treat it as a re-anchor rather than as motion. rad/s. */
    private static final double MAX_SPIN = 60.0;

    /** Beyond this the pivot teleported. m/s. */
    private static final double MAX_PIVOT_SPEED = 600.0;

    private static final double DT = CfConfig.TICK;

    private final Vector3d omegaMeasured = new Vector3d();
    private final Vector3d omegaPrevious = new Vector3d();

    /** Filter states, held in the DECK's frame where a steady spin is a constant. */
    private final Vector3d omegaDeck = new Vector3d();
    private final Vector3d alphaDeck = new Vector3d();
    private final Vector3d pivotAccelDeck = new Vector3d();

    private final Vector3d omega = new Vector3d();
    private final Vector3d omegaLead = new Vector3d();
    private final Vector3d alpha = new Vector3d();

    private final Vector3d pivot = new Vector3d();
    private final Vector3d pivotVelocity = new Vector3d();
    private final Vector3d pivotVelocityPrevious = new Vector3d();
    private final Vector3d pivotAcceleration = new Vector3d();

    private final Vector3d scratchA = new Vector3d();
    private final Vector3d scratchB = new Vector3d();

    @Nullable
    private Object owner;

    private boolean primed;
    private boolean valid;

    public void reset() {
        this.owner = null;
        this.primed = false;
        this.valid = false;

        this.omegaMeasured.zero();
        this.omegaPrevious.zero();
        this.omegaDeck.zero();
        this.alphaDeck.zero();
        this.pivotAccelDeck.zero();
        this.omega.zero();
        this.omegaLead.zero();
        this.alpha.zero();
        this.pivot.zero();
        this.pivotVelocity.zero();
        this.pivotVelocityPrevious.zero();
        this.pivotAcceleration.zero();
    }

    /**
     * Re-measures the sub-level's motion. Call once per tick, before anything reads it.
     *
     * @return true when the readings are usable
     */
    public boolean sample(final SubLevel subLevel) {
        if (this.owner != subLevel) {
            // A different contraption, or the same one re-created by the client. Every filter state
            // below describes the old one, and blending them would invent a spin-up that never
            // happened - which the release detector would then read as a violent stop.
            this.reset();
            this.owner = subLevel;
        }

        final Pose3dc pose = subLevel.logicalPose();
        final Pose3dc last = subLevel.lastPose();

        if (pose == null || last == null) {
            this.valid = false;
            return false;
        }

        final Quaterniondc now = pose.orientation();
        final Quaterniondc then = last.orientation();

        if (now == null || then == null || !Double.isFinite(now.w()) || !Double.isFinite(then.w())) {
            this.valid = false;
            return false;
        }

        // ---- angular velocity: ONE differentiation, in world space.
        final Quaterniond delta = new Quaterniond(then).conjugate().premul(now);
        final Vector3d turn = CfMath.log(delta, this.scratchA);

        if (!turn.isFinite()) {
            this.valid = false;
            return false;
        }

        this.omegaMeasured.set(turn).div(DT);

        if (this.omegaMeasured.length() > MAX_SPIN) {
            // A teleport, a re-anchor, or a chunk boundary. Not motion.
            this.reset();
            this.owner = subLevel;
            this.valid = false;
            return false;
        }

        // ---- pivot: the world image of the rotation point, so this is the rigid translation.
        final Vector3dc position = pose.position();
        final Vector3dc lastPosition = last.position();

        this.pivot.set(position);

        this.scratchB.set(position).sub(lastPosition).div(DT);

        final boolean pivotSane = this.scratchB.isFinite()
                && this.scratchB.length() <= MAX_PIVOT_SPEED;

        if (!pivotSane) {
            this.scratchB.zero();
        }

        if (!this.primed) {
            // First usable tick. Seed every filter with the measurement instead of ramping up from
            // zero: walking onto a ride that is already spinning should not read as a spin-up.
            this.omegaPrevious.set(this.omegaMeasured);
            this.pivotVelocity.set(this.scratchB);
            this.pivotVelocityPrevious.set(this.scratchB);

            toDeck(now, this.omegaMeasured, this.omegaDeck);
            this.alphaDeck.zero();
            this.pivotAccelDeck.zero();

            this.primed = true;
        } else {
            this.pivotVelocityPrevious.set(this.pivotVelocity);
            this.pivotVelocity.set(this.scratchB);
        }

        // ---- filtering, in the deck's own frame.
        //
        // This is the detail that removes the direction error. In world space a low-pass on a
        // rotating quantity drags it backwards; in the deck's frame a steady spin is a CONSTANT
        // vector, so the same filter has nothing to drag. Orientation only - no scale - because an
        // angular velocity is not a length and must not be rescaled by a scaled sub-level.
        final Vector3d measuredDeck = toDeck(now, this.omegaMeasured, new Vector3d());

        final double omegaAlpha = CfConfig.smoothingAlpha(CfConfig.OMEGA_HALF_LIFE, DT);
        this.omegaDeck.lerp(measuredDeck, omegaAlpha);

        final Vector3d alphaMeasuredDeck = toDeck(now,
                new Vector3d(this.omegaMeasured).sub(this.omegaPrevious).div(DT), new Vector3d());

        final double alphaAlpha = CfConfig.smoothingAlpha(CfConfig.ALPHA_HALF_LIFE, DT);
        this.alphaDeck.lerp(alphaMeasuredDeck, alphaAlpha);

        final Vector3d pivotAccelMeasuredDeck = toDeck(now,
                new Vector3d(this.pivotVelocity).sub(this.pivotVelocityPrevious).div(DT),
                new Vector3d());

        final double pivotAlpha = CfConfig.smoothingAlpha(CfConfig.PIVOT_ACCEL_HALF_LIFE, DT);
        this.pivotAccelDeck.lerp(pivotAccelMeasuredDeck, pivotAlpha);

        this.omegaPrevious.set(this.omegaMeasured);

        // ---- back to world.
        toWorld(now, this.omegaDeck, this.omega);
        toWorld(now, this.alphaDeck, this.alpha);
        toWorld(now, this.pivotAccelDeck, this.pivotAcceleration);

        // ---- phase lead.
        //
        // omega as measured is centred half a tick in the past, and Sable's pose itself is a tick
        // behind the server on a client. Extrapolating with alpha cancels a delay we can quantify,
        // which is a fix; leaving it in and compensating elsewhere would be a fudge. Capped at the
        // magnitude of omega so a noisy alpha can never reverse the spin.
        final double lead = CfConfig.CENTRIFUGAL_LEAD.get() * DT;

        this.omegaLead.set(this.omega);

        if (lead > 0.0) {
            final Vector3d correction = new Vector3d(this.alpha).mul(lead);

            CfMath.clampAngle(correction, this.omega.length());

            this.omegaLead.add(correction);
        }

        // ---- gate the purely linear term.
        final double linearGate = CfConfig.linearAccelGate(this.pivotAcceleration.length());
        this.pivotAcceleration.mul(linearGate);

        this.valid = this.omega.isFinite() && this.alpha.isFinite()
                && this.pivotAcceleration.isFinite() && this.pivot.isFinite();

        if (!this.valid) {
            this.omega.zero();
            this.omegaLead.zero();
            this.alpha.zero();
            this.pivotAcceleration.zero();
        }

        return this.valid;
    }

    public boolean valid() {
        return this.valid;
    }

    /** Lead-compensated angular velocity, world space, rad/s. This is what the forces use. */
    public Vector3dc omega() {
        return this.omegaLead;
    }

    /** Filtered angular velocity with no lead applied. For reporting and gating. */
    public Vector3dc omegaFiltered() {
        return this.omega;
    }

    /** Angular acceleration, world space, rad/s^2. */
    public Vector3dc alpha() {
        return this.alpha;
    }

    /** World position of the sub-level's rotation point. */
    public Vector3dc pivot() {
        return this.pivot;
    }

    /** Rigid translation velocity of the contraption, m/s. Subtracted from the wind. */
    public Vector3dc pivotVelocity() {
        return this.pivotVelocity;
    }

    /** Dead-zoned linear acceleration of the contraption, m/s^2. */
    public Vector3dc linearAcceleration() {
        return this.pivotAcceleration;
    }

    public double spin() {
        return this.omega.length();
    }

    /**
     * Centripetal acceleration of the deck material at a world point: {@code omega x (omega x r)}.
     *
     * <p>Points at the axis, exactly, always. Not "after the filter settles" and not "to within the
     * lag" - it is a cross product of the current omega with the current radius, so a direction
     * error would have to come from omega's own axis being wrong, and omega's axis is the one thing
     * a steady spin holds perfectly still.</p>
     */
    public Vector3d centripetalAt(final Vector3dc worldPosition, final Vector3d dest) {
        final Vector3d r = new Vector3d(worldPosition).sub(this.pivot);

        if (!r.isFinite()) {
            return dest.zero();
        }

        final Vector3d inner = this.omegaLead.cross(r, new Vector3d());

        this.omegaLead.cross(inner, dest);

        return dest.isFinite() ? dest : dest.zero();
    }

    /** Euler (angular acceleration) term at a world point: {@code alpha x r}. */
    public Vector3d eulerAt(final Vector3dc worldPosition, final Vector3d dest) {
        final Vector3d r = new Vector3d(worldPosition).sub(this.pivot);

        if (!r.isFinite()) {
            return dest.zero();
        }

        this.alpha.cross(r, dest);

        return dest.isFinite() ? dest : dest.zero();
    }

    /** Total acceleration of the deck material at a world point, m/s^2. */
    public Vector3d accelerationAt(final Vector3dc worldPosition, final Vector3d dest) {
        final Vector3d centripetal = this.centripetalAt(worldPosition, new Vector3d());
        final Vector3d euler = this.eulerAt(worldPosition, new Vector3d());

        dest.set(this.pivotAcceleration).add(euler).add(centripetal);

        return dest.isFinite() ? dest : dest.zero();
    }

    /** Velocity of the deck material at a world point, m/s: {@code v_pivot + omega x r}. */
    public Vector3d velocityAt(final Vector3dc worldPosition, final Vector3d dest) {
        final Vector3d r = new Vector3d(worldPosition).sub(this.pivot);

        if (!r.isFinite()) {
            return dest.zero();
        }

        this.omega.cross(r, dest).add(this.pivotVelocity);

        return dest.isFinite() ? dest : dest.zero();
    }

    // ------------------------------------------------------------------ frame conversion

    /**
     * World to deck, orientation only.
     *
     * <p>Deliberately not {@code Pose3dc#transformNormalInverse}, which also carries the
     * sub-level's scale. Scale is right for a displacement and wrong for an angular velocity: a
     * contraption built at half size does not spin at twice the rate.</p>
     */
    private static Vector3d toDeck(final Quaterniondc orientation,
                                   final Vector3dc world, final Vector3d dest) {

        new Quaterniond(orientation).conjugate().transform(world.x(), world.y(), world.z(), dest);

        return dest.isFinite() ? dest : dest.zero();
    }

    private static Vector3d toWorld(final Quaterniondc orientation,
                                    final Vector3dc deck, final Vector3d dest) {

        new Quaterniond(orientation).transform(deck.x(), deck.y(), deck.z(), dest);

        return dest.isFinite() ? dest : dest.zero();
    }
}
