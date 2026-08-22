package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;

/**
 * Turns the body frame into actual movement: drag, friction, sliding, being thrown off.
 *
 * <h2>The three states, and why they are one formula</h2>
 *
 * <p>Standing, sliding and being swept off are not three code paths with thresholds between them.
 * They are the same Coulomb friction comparison - can the feet hold the sideways load - evaluated
 * every tick. The load grows smoothly with speed, so the transitions arrive smoothly too, and the
 * middle state is a real state you can be in and fight rather than a frame you pass through.</p>
 *
 * <h2>Where the rotation enters the sweep-off</h2>
 *
 * <p>Two separate places, and missing either one is what made being flung off a tilted spinner feel
 * wrong:</p>
 *
 * <ol>
 *   <li><b>Air speed is the player's world velocity, not their walking velocity.</b> Sable carries
 *       a standing player around with the deck, so their {@code deltaMovement} is relative to the
 *       deck and is nearly zero while they stand still - but they are still being dragged through
 *       the air at the deck's tangential speed, {@code omega x r}, which on a 5-block arm at 3 rad/s
 *       is 15 m/s. Drag has to see that. This is the term whose absence made a fast spinner behave
 *       like a gently tilted floor.</li>
 *   <li><b>The frame acceleration is Sable's own velocity field differentiated in time</b>, so it
 *       contains the centrifugal term, the Euler term from the spin changing rate, and any linear
 *       acceleration of the contraption - see {@link BodyFrame}. The old hand-rolled version had
 *       only the first of the three.</li>
 * </ol>
 *
 * <h2>Why velocity is added rather than set</h2>
 *
 * <p>Sable resolves collisions in up to eight substeps per tick and Sure Footing rewrites tracking
 * every tick in mid-air. Assigning a velocity means whoever writes last wins, and the result is a
 * fight that shows up as stutter. Adding an increment composes with both of them instead.</p>
 */
public final class CentrifugalHandler {

    /**
     * m/s^2 to blocks/tick. One factor of 20 converts per-second to per-tick, the other converts
     * m/s to blocks/tick, because Minecraft velocities are blocks per tick and Sable's are m/s.
     */
    private static final double ACCEL_TO_DELTA = 1.0 / 400.0;

    /** cos(60 degrees): steeper than this and it is a wall, not a slope. Display only. */
    private static final double WALL_COSINE = 0.5;

    public static final ForceState STATE = new ForceState();

    @SubscribeEvent
    public void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null || minecraft.isPaused() || !CfConfig.SPEC.isLoaded()) {
            STATE.clear();
            return;
        }

        if (!(player instanceof BodyFrameHolder holder)) {
            STATE.clear();
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();
        final SubLevel subLevel = SableAccess.tracking(player);

        if (frame == null || !frame.isActive() || subLevel == null || subLevel.isRemoved()) {
            STATE.clear();
            return;
        }

        if (player.isPassenger() || player.isSpectator() || player.getAbilities().flying) {
            STATE.clear();
            return;
        }

        final Vec3 position = player.position();

        // --- velocities ---------------------------------------------------------------

        // The deck's own velocity here. This is omega x r plus any drift, computed by Sable from
        // its pose delta, so it is exact and needs no rotation centre guessed on this side.
        final Vec3 deck = SableAccess.pointVelocity(subLevel, position);

        final Vec3 own = player.getDeltaMovement().scale(20.0);

        // World velocity = carried by the deck + walking on top of it. The first term is the one
        // that makes a spinner able to throw you off at all.
        final Vector3d world = new Vector3d(deck.x + own.x, deck.y + own.y, deck.z + own.z);

        final Vec3 wind = SableAccess.wind(minecraft.level, position);

        final Vector3d air = new Vector3d(world).sub(wind.x, wind.y, wind.z);

        // --- accelerations -----------------------------------------------------------

        final Vector3d apparent = new Vector3d(frame.apparent());

        final Vector3d coriolis = new Vector3d();

        if (CfConfig.CENTRIFUGAL_ENABLED.get()) {
            final double coriolisStrength = CfConfig.CORIOLIS_STRENGTH.get();

            if (coriolisStrength > 0.0) {
                // -2 omega x v_rel. v_rel is the player's own movement, which is why this term and
                // no other has to be kept out of the camera's target.
                new Vector3d(frame.omega()).cross(own.x, own.y, own.z, coriolis)
                        .mul(-2.0 * coriolisStrength);

                if (!coriolis.isFinite()) {
                    coriolis.zero();
                }

                apparent.add(coriolis);
            }
        }

        final Vector3d drag = new Vector3d();

        if (CfConfig.AIR_ENABLED.get()) {
            final double airSpeed = air.length();

            if (airSpeed > 1.0e-4) {
                drag.set(air).div(airSpeed).mul(-CfConfig.dragMagnitude(airSpeed));

                if (!drag.isFinite()) {
                    drag.zero();
                }
            }
        }

        // --- grip decision -----------------------------------------------------------

        final Vector3d normal = new Vector3d(frame.normal());

        // Deliberate and readable: a surface holds you when the load pressing you into it exceeds a
        // threshold. Nothing here depends on which collision face Sable happened to resolve last,
        // which is what made sticking to a drum wall a matter of luck before.
        final double press = -apparent.dot(normal);

        final boolean touching = player.onGround()
                || player.verticalCollision
                || player.horizontalCollision
                || frame.tilt() > 0.35;

        final boolean gripEnabled = CfConfig.GRIP_ENABLED.get();

        final boolean gripped = touching
                && gripEnabled
                && press >= CfConfig.GRIP_MIN_PRESS_G.get() * CfConfig.GRAVITY;

        // Total load, including drag, resolved into "into the surface" and "along the surface".
        final Vector3d total = new Vector3d(apparent).add(drag);

        final Vector3d tangential = new Vector3d(total)
                .sub(new Vector3d(normal).mul(total.dot(normal)));

        final double tangentialLoad = tangential.length();

        final boolean bracing = player.isShiftKeyDown();

        final double hold = gripped
                ? CfConfig.GRIP_STRENGTH.get() * press * (bracing ? CfConfig.GRIP_BRACE_BONUS.get() : 1.0)
                : 0.0;

        final Vector3d applied = new Vector3d();
        boolean slipping = false;

        if (!gripped) {
            // Nothing holding you: the full felt acceleration applies and you go where it points.
            // On a spinner that is outward and tangential, which is the correct way to leave.
            applied.set(total);
            slipping = true;
        } else if (tangentialLoad <= hold) {
            // Static friction wins. Cancel the slide exactly - not approximately, or you creep.
            applied.set(tangential).negate();
        } else {
            // Kinetic: friction removes what it can and the rest accelerates you along the surface.
            // The residual fraction is what makes sliding controllable instead of instant.
            final double residual = 1.0 - hold / tangentialLoad;
            applied.set(tangential).mul(residual);
            slipping = true;
        }

        // Safety clamp. A contraption that teleports produces one tick of nonsense acceleration,
        // and without this that single tick launches the player out of the world.
        final double limit = CfConfig.MAX_ACCEL_G.get() * CfConfig.GRAVITY;
        final double magnitude = applied.length();

        if (magnitude > limit && magnitude > 0.0) {
            applied.mul(limit / magnitude);
        }

        if (!applied.isFinite()) {
            applied.zero();
        }

        player.setDeltaMovement(player.getDeltaMovement().add(
                applied.x * ACCEL_TO_DELTA,
                applied.y * ACCEL_TO_DELTA,
                applied.z * ACCEL_TO_DELTA));

        // Being pressed into a surface is not falling. Without this, standing inside a drum bills
        // you for fall damage the moment you touch anything.
        if (press > 1.5 * CfConfig.GRAVITY) {
            player.resetFallDistance();
        }

        // --- publish ------------------------------------------------------------------

        STATE.active = true;
        STATE.frameAcceleration.set(frame.frameAcceleration());
        STATE.coriolis.set(coriolis);
        STATE.drag.set(drag);
        STATE.apparent.set(apparent);
        STATE.applied.set(applied);
        STATE.airVelocity.set(air);
        STATE.deckVelocity.set(deck.x, deck.y, deck.z);
        STATE.relativeVelocity.set(own.x, own.y, own.z);
        STATE.omega.set(frame.omega());
        STATE.angularAcceleration.set(frame.angularAcceleration());
        STATE.normal.set(normal);
        STATE.press = press;
        STATE.tangentialLoad = tangentialLoad;
        STATE.hold = hold;
        STATE.tilt = frame.tilt();
        STATE.gripped = gripped;
        STATE.slipping = slipping;
        STATE.bracing = bracing;
        STATE.wallRide = gripped && normal.y() < WALL_COSINE;

        // Slip is what you would see: motion along the surface relative to the deck.
        STATE.slip.set(own.x, own.y, own.z);
        STATE.slip.sub(new Vector3d(normal).mul(STATE.slip.dot(normal)));

        decomposeFrameAcceleration(frame, position, subLevel);
    }

    /**
     * Splits the frame acceleration into a centrifugal part and everything else, for the arrows.
     *
     * <p>Display only, and approximate on purpose. The physics never needs the split - it uses the
     * total, which is exact - but an arrow labelled "centrifugal" that actually shows centrifugal
     * plus Euler plus linear drift is not a debugging aid.</p>
     *
     * <p>Centrifugal always points directly away from the rotation axis, so the component of the
     * frame acceleration along the radial direction is the centrifugal part, and the remainder is
     * Euler plus linear. The radial direction comes from the pose, whose world origin is the
     * rotation centre by construction: {@code transformPosition} maps the rotation point to the
     * position, so that is the one point of the sub-level that rotation leaves alone.</p>
     */
    private static void decomposeFrameAcceleration(
            final BodyFrame frame, final Vec3 position, final SubLevel subLevel) {

        final Vector3d total = new Vector3d(frame.frameAcceleration());
        final Vector3d omega = new Vector3d(frame.omega());

        final double spin = omega.length();

        if (spin < 1.0e-4) {
            STATE.centrifugal.zero();
            STATE.euler.set(total);
            return;
        }

        final Vector3d axis = new Vector3d(omega).div(spin);

        final Vector3d centre = subLevel.logicalPose().position() instanceof org.joml.Vector3dc c
                ? new Vector3d(c)
                : new Vector3d();

        final Vector3d radius = new Vector3d(position.x, position.y, position.z).sub(centre);

        // Only the part of the radius perpendicular to the axis matters; sliding along the axis is
        // not going round anything.
        radius.sub(new Vector3d(axis).mul(radius.dot(axis)));

        final double distance = radius.length();

        if (distance < 1.0e-4) {
            STATE.centrifugal.zero();
            STATE.euler.set(total);
            return;
        }

        radius.div(distance);

        final double radial = total.dot(radius);

        STATE.centrifugal.set(radius).mul(radial);
        STATE.euler.set(total).sub(STATE.centrifugal);

        if (!STATE.centrifugal.isFinite()) {
            STATE.centrifugal.zero();
        }

        if (!STATE.euler.isFinite()) {
            STATE.euler.zero();
        }
    }
}
