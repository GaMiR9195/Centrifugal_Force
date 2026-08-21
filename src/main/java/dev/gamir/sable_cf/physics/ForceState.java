package dev.gamir.sable_cf.physics;

import org.joml.Vector3d;

/**
 * What the last physics tick worked out, for the camera and the overlay to read.
 *
 * <p>One mutable snapshot rather than recomputation on the render thread, for the same reason ACS
 * hands out a snapshot: the camera and the arrows must agree with each other and with the velocity
 * that was actually applied. Recomputing per frame would have the arrows describing a tick that
 * never happened.</p>
 *
 * <p>Written on the client tick, read on the render thread - both the client main thread, so no
 * synchronisation is needed. Accelerations are m/s^2, velocities m/s.</p>
 */
public final class ForceState {

    /** True when the player is on a rotating sub-level and the numbers below mean something. */
    public boolean active;

    public final Vector3d centrifugal = new Vector3d();
    public final Vector3d euler = new Vector3d();
    public final Vector3d coriolis = new Vector3d();
    public final Vector3d drag = new Vector3d();

    /** Gravity plus the rotating-frame terms: what the inner ear would call "down". */
    public final Vector3d apparent = new Vector3d();

    /** What we actually added to the player's velocity this tick. */
    public final Vector3d applied = new Vector3d();

    /** Unit normal of the surface being stood on, world space. */
    public final Vector3d normal = new Vector3d();

    /** Player velocity through the air, m/s - the thing drag is computed from. */
    public final Vector3d airVelocity = new Vector3d();

    /** Velocity of the deck point under the player, m/s. */
    public final Vector3d deckVelocity = new Vector3d();

    public final Vector3d omega = new Vector3d();

    /** Normal load, m/s^2. Divide by CfConfig.GRAVITY for g. */
    public double press;

    /** What friction can hold, m/s^2. */
    public double hold;

    /** What is trying to slide you, m/s^2. */
    public double tangentialLoad;

    public boolean gripped;
    public boolean slipping;
    public boolean bracing;

    public void clear() {
        this.active = false;
        this.gripped = false;
        this.slipping = false;
        this.bracing = false;
        this.press = 0.0;
        this.hold = 0.0;
        this.tangentialLoad = 0.0;
        this.centrifugal.zero();
        this.euler.zero();
        this.coriolis.zero();
        this.drag.zero();
        this.apparent.set(0.0, -1.0, 0.0);
        this.applied.zero();
        this.normal.set(0.0, 1.0, 0.0);
        this.airVelocity.zero();
        this.deckVelocity.zero();
        this.omega.zero();
    }
}
