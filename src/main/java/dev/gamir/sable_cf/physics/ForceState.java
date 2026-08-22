package dev.gamir.sable_cf.physics;

import org.joml.Vector3d;

/**
 * Last tick's numbers, for the camera, the arrows and {@code /sable_cf status}.
 *
 * <p>Deliberately a mutable bag of vectors that is reused rather than reallocated: it is written
 * once per tick and read several times per frame by the renderer, and allocating a fresh object
 * per tick to be read at 200 fps is the wrong trade.</p>
 *
 * <p>The reason it exists at all is that the camera must not recompute any of this. If it did,
 * there would be two implementations of the same physics running at different rates, and they
 * would disagree - which is precisely the class of bug where the camera leans one way and the
 * player slides the other.</p>
 */
public final class ForceState {

    /** True when the player is on a sub-level and the numbers below mean something. */
    public boolean active;

    // ---------------------------------------------------------------- accelerations, m/s^2

    /**
     * The deck's own acceleration at the player's position: centrifugal, Euler and any linear
     * acceleration of the whole contraption, all together, straight from Sable's velocity field.
     */
    public final Vector3d frameAcceleration = new Vector3d();

    /** Centrifugal part of the above, separated for display only. */
    public final Vector3d centrifugal = new Vector3d();

    /** Everything in the frame acceleration that is not centrifugal: Euler plus linear. */
    public final Vector3d euler = new Vector3d();

    /**
     * Coriolis, {@code -2 omega x v_rel}. Kept separate from everything else on purpose: it is the
     * only term driven by the player's own walking rather than by the ride, which makes it the term
     * the camera has to ignore. Mixing it in is what made ordinary walking on a turntable nauseous.
     */
    public final Vector3d coriolis = new Vector3d();

    /** Air drag. */
    public final Vector3d drag = new Vector3d();

    /** Gravity plus every fictitious term: what the player's inner ear would report. */
    public final Vector3d apparent = new Vector3d();

    /** What was actually added to the player's velocity, after friction and clamping. */
    public final Vector3d applied = new Vector3d();

    // ---------------------------------------------------------------- velocities, m/s

    /** Player velocity through the air. Includes the deck's tangential velocity. */
    public final Vector3d airVelocity = new Vector3d();

    /** The deck's world velocity at the player's position - the omega x r contribution. */
    public final Vector3d deckVelocity = new Vector3d();

    /** The player's velocity relative to the deck. Their own walking, essentially. */
    public final Vector3d relativeVelocity = new Vector3d();

    /** How fast the player is sliding across the surface, m/s. */
    public final Vector3d slip = new Vector3d();

    // ---------------------------------------------------------------- frame

    /** Sub-level angular velocity, rad/s. */
    public final Vector3d omega = new Vector3d();

    /** Sub-level angular acceleration, rad/s^2. What a sharp manoeuvre actually looks like. */
    public final Vector3d angularAcceleration = new Vector3d();

    /** Surface normal the player is standing on, world space, unit. */
    public final Vector3d normal = new Vector3d(0.0, 1.0, 0.0);

    // ---------------------------------------------------------------- scalars

    /** Normal load, m/s^2. Above one gravity you are being pressed harder than by gravity alone. */
    public double press;

    /** Tangential load trying to slide the player, m/s^2. */
    public double tangentialLoad;

    /** Most the feet can hold, m/s^2. Sliding starts when the load exceeds this. */
    public double hold;

    /** How far the body has rotated towards the surface, 0..1. */
    public double tilt;

    /** Pressed hard enough into a surface for it to count as footing. */
    public boolean gripped;

    /** Gripped, but the load has beaten friction and the player is sliding. */
    public boolean slipping;

    /** Sneaking, so friction is boosted. */
    public boolean bracing;

    /** Held on to a surface steeper than 60 degrees - the drum-wall case. */
    public boolean wallRide;

    public void clear() {
        this.active = false;
        this.frameAcceleration.zero();
        this.centrifugal.zero();
        this.euler.zero();
        this.coriolis.zero();
        this.drag.zero();
        this.apparent.zero();
        this.applied.zero();
        this.airVelocity.zero();
        this.deckVelocity.zero();
        this.relativeVelocity.zero();
        this.slip.zero();
        this.omega.zero();
        this.angularAcceleration.zero();
        this.normal.set(0.0, 1.0, 0.0);
        this.press = 0.0;
        this.tangentialLoad = 0.0;
        this.hold = 0.0;
        this.tilt = 0.0;
        this.gripped = false;
        this.slipping = false;
        this.bracing = false;
        this.wallRide = false;
    }
}
