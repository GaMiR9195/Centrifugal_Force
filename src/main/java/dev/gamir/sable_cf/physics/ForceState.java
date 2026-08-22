package dev.gamir.sable_cf.physics;

import org.joml.Vector3d;

/**
 * A readable snapshot of one tick of physics: what was measured, what was decided, what was
 * applied.
 *
 * <p>Mutable and reused rather than allocated, because it is refilled every tick for every player
 * and read by the overlay every frame. Nothing here feeds back into the simulation - if a field
 * were load-bearing it would live on {@link BodyFrame} instead. That separation is deliberate: it
 * means the debug overlay can be as detailed as it likes without any risk of the act of observing
 * changing the result.</p>
 */
public final class ForceState {

    // ------------------------------------------------------------------ what is going on

    /** True when the player is on a sub-level and the mod has something to say about it. */
    public boolean active;

    /** True on the ticks where a sudden loss of ride is being smoothed out. */
    public boolean released;

    /** How many of the six body faces are touching sub-level geometry. */
    public int contactCount;

    // ------------------------------------------------------------------ the frame

    /** Angular velocity of the sub-level, world space, rad/s. Lead compensated. */
    public final Vector3d omega = new Vector3d();

    /** Angular acceleration of the sub-level, world space, rad/s^2. */
    public final Vector3d angularAcceleration = new Vector3d();

    /** 0..1 ramp on how much the sub-level counts as "spinning". */
    public double spinGate;

    /** Velocity of the deck material under the player, m/s. */
    public final Vector3d deckVelocity = new Vector3d();

    /** Rigid translation of the contraption, m/s. */
    public final Vector3d deckTranslation = new Vector3d();

    /** Player velocity minus deck velocity, m/s. What sliding is measured in. */
    public final Vector3d relativeVelocity = new Vector3d();

    // ------------------------------------------------------------------ accelerations, m/s^2

    /** Centrifugal: outward, the felt half of {@code omega x (omega x r)}. */
    public final Vector3d centrifugal = new Vector3d();

    /** Euler: the felt half of {@code alpha x r}. Spin-up and spin-down. */
    public final Vector3d euler = new Vector3d();

    /** Coriolis: {@code -2 omega x v_rel}. Only nonzero while moving across the deck. */
    public final Vector3d coriolis = new Vector3d();

    /** Felt acceleration from the contraption translating as a whole. Heavily dead-zoned. */
    public final Vector3d linear = new Vector3d();

    /** Air resistance, m/s^2. The one thing allowed to unstick you. */
    public final Vector3d drag = new Vector3d();

    /** Everything felt in the rotating frame, gravity included. */
    public final Vector3d apparent = new Vector3d();

    /** What was actually added to the player's velocity this tick, as an acceleration. */
    public final Vector3d applied = new Vector3d();

    // ------------------------------------------------------------------ air

    /** Player velocity relative to the air, m/s. */
    public final Vector3d airVelocity = new Vector3d();

    public double airSpeed;

    // ------------------------------------------------------------------ the floor

    /** World unit normal of the committed plane. World up when there is none. */
    public final Vector3d normal = new Vector3d(0.0, 1.0, 0.0);

    /** Committed face index, or {@link GravityPlane#NONE}. */
    public int planeIndex = GravityPlane.NONE;

    /** Face currently arguing for a switch, or {@link GravityPlane#NONE}. */
    public int challengerIndex = GravityPlane.NONE;

    /** How many consecutive ticks the challenger has been winning by the margin. */
    public int challengerTicks;

    // ------------------------------------------------------------------ wall walking

    /** Total press into the plane including gravity, m/s^2. Negative means being pulled off. */
    public double press;

    /** Press from the ride alone, gravity excluded, m/s^2. This is what earns the stick. */
    public double ridePress;

    /** 0..1. How much of a wall-walker the player currently is. The headline number. */
    public double stick;

    /** 0..1 ramp on how much grip the remaining contact is worth. */
    public double footing;

    /** Tangential load the friction solve had to deal with, m/s^2. */
    public double tangentialLoad;

    /** How much of it friction actually held, m/s^2. */
    public double hold;

    /** Acceleration that friction could not hold, m/s^2. This is the slide. */
    public final Vector3d slip = new Vector3d();

    public boolean gripped;

    public boolean slipping;

    /** True while the player is actively holding a movement key into the surface. */
    public boolean bracing;

    /** True once the player is more on a wall than on a floor. */
    public boolean wallRide;

    // ------------------------------------------------------------------ the body

    /** Angle of the smoothed body orientation away from upright, degrees. */
    public double bodyAngleDeg;

    /** Angle of the orientation actually handed to Sable's collision, degrees. */
    public double hitboxAngleDeg;

    /** True on ticks where the hitbox was held back because the posture would not fit. */
    public boolean clearanceBlocked;

    /** Resets everything to "nothing is happening". */
    public void clear() {
        this.active = false;
        this.released = false;
        this.contactCount = 0;

        this.omega.zero();
        this.angularAcceleration.zero();
        this.spinGate = 0.0;
        this.deckVelocity.zero();
        this.deckTranslation.zero();
        this.relativeVelocity.zero();

        this.centrifugal.zero();
        this.euler.zero();
        this.coriolis.zero();
        this.linear.zero();
        this.drag.zero();
        this.apparent.zero();
        this.applied.zero();

        this.airVelocity.zero();
        this.airSpeed = 0.0;

        this.normal.set(0.0, 1.0, 0.0);
        this.planeIndex = GravityPlane.NONE;
        this.challengerIndex = GravityPlane.NONE;
        this.challengerTicks = 0;

        this.press = 0.0;
        this.ridePress = 0.0;
        this.stick = 0.0;
        this.footing = 0.0;
        this.tangentialLoad = 0.0;
        this.hold = 0.0;
        this.slip.zero();

        this.gripped = false;
        this.slipping = false;
        this.bracing = false;
        this.wallRide = false;

        this.bodyAngleDeg = 0.0;
        this.hitboxAngleDeg = 0.0;
        this.clearanceBlocked = false;
    }
}
