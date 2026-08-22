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
import org.joml.Vector3dc;

/**
 * Where the forces are actually applied.
 *
 * <h2>What gets applied, and what deliberately does not</h2>
 *
 * <p>Only <b>fictitious</b> terms: the deck's frame acceleration, Coriolis, drag, and the two
 * deliberate gameplay pushes (outward slip, surface climb). Gravity is never applied here - vanilla
 * already does that. Getting this wrong doubles gravity, and it is the reason {@code apparent}
 * exists purely as a reported quantity rather than as something summed into the player's
 * velocity.</p>
 *
 * <p>The consequence worth stating plainly: on a sub-level that is merely travelling - a lift, a
 * ship under way, a drawbridge - every one of those terms is zero, so this handler adds exactly
 * nothing and movement is bit-for-bit vanilla. That is not a tuned threshold, it is what the terms
 * evaluate to.</p>
 *
 * <h2>Velocity across the deck is measured, not inferred</h2>
 *
 * <p>Sable carries a standing player by moving their <i>position</i> through the pose, and never
 * touches {@code deltaMovement}. So {@code deltaMovement} is blind to sliding across the deck: a
 * player being dragged outward at three metres a second reports zero. Every speed limit here reads
 * {@link BodyFrame#deckRelativeVelocity()} instead, which is differenced from the player's local
 * position and therefore sees exactly what is happening. A limiter that cannot observe the quantity
 * it limits is not a limiter, and that is precisely how a capped outward creep became an
 * accelerating shove that pushed everyone off every spinning contraption.</p>
 *
 * <h2>Drag is measured against the air the deck carries</h2>
 *
 * <p>The rigid translation is subtracted before computing drag. Feeding a player's world velocity
 * to a drag law tells someone standing still on a deck cruising at 25 m/s that they are in a 25 m/s
 * gale. What survives the subtraction is exactly what should: rotation does not move the pose's
 * rotation centre, so {@code omega x r} is untouched and a spinner still tries to peel you off,
 * while pure translation cancels to zero identically.</p>
 *
 * <p>That peeling is the intended purpose of drag in this mod. Wall riding should cost something,
 * and being carried through still air at {@code omega x r} is what makes holding on a thing you do
 * rather than a thing that happens.</p>
 *
 * <h2>One friction comparison, not three states</h2>
 *
 * <p>Standing, sliding slowly, sliding fast and being swept off are not four branches. They are one
 * Coulomb comparison - friction holds up to {@code mu * press} and only the excess moves you - so
 * the transitions are continuous by construction and "nearly holding on" is a real, playable state
 * rather than a knife edge.</p>
 */
public final class CentrifugalHandler {

    /**
     * Acceleration in m/s^2 to a velocity delta in blocks/tick: {@code a/20} m/s over one tick,
     * divided by 20 again to get blocks/tick. Minecraft velocities are per-tick, and forgetting
     * the second division is a factor-of-twenty error that looks like the mod working perfectly on
     * one machine.
     */
    public static final double ACCEL_TO_DELTA = 1.0 / 400.0;

    /** Above this press, in g, fall damage is suppressed: you are being held, not falling. */
    private static final double FALL_RESET_G = 1.5;

    public static final ForceState STATE = new ForceState();

    private static final Vector3dc WORLD_UP = new Vector3d(0.0, 1.0, 0.0);

    @SubscribeEvent
    public void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;

        if (player == null || minecraft.isPaused() || !CfConfig.SPEC.isLoaded()) {
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

        STATE.clear();
        STATE.active = true;

        final double gravity = CfConfig.GRAVITY;
        final Vec3 position = player.position();

        // ------------------------------------------------------------ velocities

        final Vec3 deckVec = SableAccess.pointVelocity(subLevel, position);
        final Vector3d deck = new Vector3d(deckVec.x, deckVec.y, deckVec.z);
        final Vector3d translation = new Vector3d(frame.deckTranslation());

        // Measured, not inferred. See the class note.
        final Vector3d deckRelative = new Vector3d(frame.deckRelativeVelocity());

        if (!deckRelative.isFinite()) {
            deckRelative.zero();
        }

        // The player's walking effort, for Coriolis and for the overlay.
        final Vec3 ownVec = player.getDeltaMovement().scale(20.0);
        final Vector3d own = new Vector3d(ownVec.x, ownVec.y, ownVec.z);

        if (!own.isFinite()) {
            own.zero();
        }

        final Vec3 windVec = SableAccess.wind(player.level(), position);

        // deck - translation is exactly the rotational part, omega x r. See the class note.
        final Vector3d air = new Vector3d(deck).sub(translation).add(deckRelative)
                .sub(windVec.x, windVec.y, windVec.z);

        if (!air.isFinite()) {
            air.zero();
        }

        STATE.deckVelocity.set(deck);
        STATE.deckTranslation.set(translation);
        STATE.deckRelativeVelocity.set(deckRelative);
        STATE.relativeVelocity.set(own);
        STATE.airVelocity.set(air);
        STATE.omega.set(frame.omega());
        STATE.angularAcceleration.set(frame.angularAcceleration());
        STATE.normal.set(frame.normal());
        STATE.frameAcceleration.set(frame.frameAcceleration());
        STATE.apparent.set(frame.apparent());
        STATE.tilt = frame.tilt();
        STATE.ridePress = frame.ridePress();
        STATE.frameShare = frame.frameShare();
        STATE.spinGate = frame.spinGate();
        STATE.contactCount = frame.contacts().count();
        STATE.attached = frame.isAttached();
        STATE.attachNormal.set(frame.attachNormal());

        // The camera's target. Derived from the same orientation the hitbox uses, so the two
        // cannot drift apart into "the view leans one way and the body is somewhere else".
        STATE.bodyUp.set(0.0, 1.0, 0.0);
        frame.orientation().transform(STATE.bodyUp);

        if (!STATE.bodyUp.isFinite() || STATE.bodyUp.lengthSquared() < 1.0e-9) {
            STATE.bodyUp.set(0.0, 1.0, 0.0);
        }

        // ------------------------------------------------------------ the launch case

        if (frame.releaseTriggered()) {
            this.launch(player, frame);
            frame.consumeRelease();
            STATE.released = true;
            return;
        }

        // Jumping is always allowed to break a latch. Being stuck to a wall with no way off is a
        // trap, and the player pressing jump is the least ambiguous "let go" signal there is.
        if (frame.isAttached() && player.input != null && player.input.jumping) {
            frame.detach();
        }

        // ------------------------------------------------------------ the terms

        final double strength = CfConfig.CENTRIFUGAL_ENABLED.get()
                ? CfConfig.CENTRIFUGAL_STRENGTH.get()
                : 0.0;

        // What the player feels as being flung: the reaction to the deck's own acceleration.
        final Vector3d rideLoad = new Vector3d(frame.frameAcceleration()).mul(-strength);

        this.splitCentrifugal(frame, rideLoad, strength);

        final Vector3d coriolis = new Vector3d();

        if (strength > 0.0 && CfConfig.CORIOLIS_STRENGTH.get() > 0.0) {
            // -2 omega x v_rel. Only the player's own motion counts: the deck's own velocity is
            // already accounted for by the frame acceleration, and double counting it turns a
            // turntable into a catapult.
            frame.omega().cross(own.x, own.y, own.z, coriolis);
            coriolis.mul(-2.0 * CfConfig.CORIOLIS_STRENGTH.get());

            if (!coriolis.isFinite()) {
                coriolis.zero();
            }
        }

        final Vector3d drag = new Vector3d();

        if (CfConfig.AIR_ENABLED.get()) {
            final double airSpeed = air.length();
            final double magnitude = CfConfig.dragMagnitude(airSpeed);

            if (airSpeed > 1.0e-6 && magnitude > 0.0) {
                drag.set(air).div(airSpeed).mul(-magnitude);
            }
        }

        STATE.coriolis.set(coriolis);
        STATE.drag.set(drag);

        // ------------------------------------------------------------ friction

        final Vector3d normal = new Vector3d(frame.normal());

        if (normal.lengthSquared() < 1.0e-9) {
            normal.set(WORLD_UP);
        } else {
            normal.normalize();
        }

        // Everything this mod would like to add, before friction gets a say.
        final Vector3d load = new Vector3d(rideLoad).add(coriolis).add(drag);

        // Total normal load includes gravity, because friction does not care where the press came
        // from - it is what is squeezing your boots against the surface.
        final double press = Math.max(0.0, -(new Vector3d(frame.apparent()).add(drag)).dot(normal));

        final boolean bracing = player.isShiftKeyDown();
        final boolean hasSurface = frame.contacts().any();

        final double friction = CfConfig.GRIP_ENABLED.get()
                ? CfConfig.GRIP_STRENGTH.get() * (bracing ? CfConfig.GRIP_BRACE_BONUS.get() : 1.0)
                : 0.0;

        final double hold = hasSurface ? friction * press : 0.0;

        final double normalComponent = load.dot(normal);
        final Vector3d tangential = new Vector3d(load)
                .sub(normal.x * normalComponent, normal.y * normalComponent, normal.z * normalComponent);

        final double tangentialLoad = tangential.length();

        STATE.press = press;
        STATE.hold = hold;
        STATE.tangentialLoad = tangentialLoad;
        STATE.bracing = bracing;
        STATE.gripped = hasSurface && press > CfConfig.GRIP_MIN_PRESS_G.get() * gravity;
        STATE.wallRide = STATE.gripped && Math.abs(normal.dot(WORLD_UP)) < CfConfig.WALL_COSINE;

        final Vector3d applied = new Vector3d();

        // The normal component always applies. If it points into the surface it is what pins you
        // there and collision eats it, which is correct; if it points away it is what lifts you off.
        applied.add(normal.x * normalComponent, normal.y * normalComponent, normal.z * normalComponent);

        if (tangentialLoad > 1.0e-9 && tangentialLoad > hold) {
            // One comparison, and the entire stand / slide / swept-off spectrum falls out of it.
            final double excess = (tangentialLoad - hold) / tangentialLoad;

            applied.add(tangential.x * excess, tangential.y * excess, tangential.z * excess);

            STATE.slipping = STATE.gripped;
            STATE.slip.set(tangential).mul(excess / 20.0);
        }

        // ------------------------------------------------------------ deliberate pushes

        applied.add(this.outwardSlip(frame, rideLoad, normal, deckRelative));
        applied.add(this.surfaceClimb(frame, normal, press, deckRelative));
        applied.add(this.adhesion(frame));

        // ------------------------------------------------------------ apply

        if (!applied.isFinite()) {
            return;
        }

        final double cap = CfConfig.MAX_ACCEL_G.get() * gravity;
        final double magnitude = applied.length();

        // A contraption that teleports produces one tick of enormous bogus acceleration. Without
        // this rail that single tick launches the player into orbit.
        if (magnitude > cap && magnitude > 1.0e-9) {
            applied.mul(cap / magnitude);
        }

        STATE.applied.set(applied);

        if (applied.lengthSquared() < 1.0e-12) {
            return;
        }

        player.setDeltaMovement(player.getDeltaMovement().add(
                applied.x * ACCEL_TO_DELTA, applied.y * ACCEL_TO_DELTA, applied.z * ACCEL_TO_DELTA));

        // Being pressed into a drum wall is not falling, whatever the vertical delta says. Note the
        // server keeps its own fall counter from position deltas, so this only fixes the client's
        // opinion - use /gamerule fallDamage false while testing rides.
        if (press > FALL_RESET_G * gravity) {
            player.fallDistance = 0.0f;
        }
    }

    /**
     * Splits the ride load into a centrifugal part and everything else, for display only.
     *
     * <p>Nothing downstream of the debug overlay depends on this split being exact - the physics
     * uses the total, which is the whole point of taking it from Sable's velocity field rather than
     * assembling it by hand.</p>
     */
    private void splitCentrifugal(
            final BodyFrame frame, final Vector3dc rideLoad, final double strength) {

        final Vector3d omega = new Vector3d(frame.omega());
        final double spin = omega.length();

        if (spin < 1.0e-6 || strength <= 0.0) {
            STATE.euler.set(rideLoad);
            return;
        }

        final Vector3d axis = new Vector3d(omega).div(spin);
        final Vector3d load = new Vector3d(rideLoad);

        // The part of the load perpendicular to the spin axis is the centrifugal one; the part
        // along the axis cannot be.
        final double alongAxis = load.dot(axis);
        final Vector3d perpendicular = new Vector3d(load)
                .sub(axis.x * alongAxis, axis.y * alongAxis, axis.z * alongAxis);

        STATE.centrifugal.set(perpendicular);
        STATE.euler.set(load).sub(perpendicular);
    }

    /**
     * The deliberate outward creep.
     *
     * <p>In a drum you should ease from the middle out to the rim and end up leaning on the lip.
     * Friction alone will not do it: friction is symmetric, so once it holds you it holds you
     * exactly where you are, and the ride never actually moves you anywhere.</p>
     *
     * <p>So a fraction of the outward load is let past friction on purpose - and then limited by
     * <i>speed</i>, against the measured deck-relative velocity. That last detail is the whole
     * difference between a drift and a disaster: the previous version asked {@code deltaMovement}
     * how fast the creep was going, {@code deltaMovement} is structurally incapable of containing
     * that motion, so the limiter always read zero and the push accumulated for as long as the ride
     * turned. The fade means it settles at the cap rather than clipping at it.</p>
     */
    private Vector3d outwardSlip(
            final BodyFrame frame, final Vector3dc rideLoad,
            final Vector3dc normal, final Vector3dc deckRelative) {

        final Vector3d result = new Vector3d();

        if (!CfConfig.SLIP_ENABLED.get()) {
            return result;
        }

        final double gate = frame.spinGate();
        final double strength = CfConfig.SLIP_STRENGTH.get();

        if (gate <= 0.0 || strength <= 0.0 || !frame.contacts().any()) {
            return result;
        }

        // Outward is where the ride is throwing you. Projected onto the surface, because sliding
        // happens along the surface - the part pointing into it is press, not slip.
        final Vector3d outward = new Vector3d(rideLoad);
        final double intoSurface = outward.dot(normal);

        outward.sub(normal.x() * intoSurface, normal.y() * intoSurface, normal.z() * intoSurface);

        final double magnitude = outward.length();

        if (magnitude < 1.0e-6) {
            return result;
        }

        outward.div(magnitude);

        final double limit = CfConfig.SLIP_MAX_SPEED.get();

        if (!(limit > 0.0)) {
            return result;
        }

        // How much of the allowance is left. Fades to nothing at the cap, so the creep approaches
        // a terminal drift instead of being cut off at one.
        final double already = deckRelative.dot(outward);
        final double room = Math.min(1.0, Math.max(0.0, (limit - already) / limit));

        if (room <= 0.0) {
            return result;
        }

        result.set(outward).mul(magnitude * strength * gate * room);
        STATE.outwardSlip.set(result);

        return result;
    }

    /**
     * Walking up a surface the ride is pressing you into.
     *
     * <p>This is the wall-riding mechanic, and it is deliberately built out of press rather than
     * out of tilt or out of being latched. A rider in a real rotor walks up the drum wall because
     * centrifugal press gives their boots enough friction to beat gravity along the wall - not
     * because the world rotated for them, and not because they are glued on. So what this does is
     * cancel a share of the along-surface gravity pull, in proportion to how hard the ride is
     * pressing. Below {@code full_press_g} nothing is cancelled and a wall is a wall; by
     * {@code rim_climb_g} it is fully cancelled and the surface handles like a floor, so ordinary
     * movement carries you up it and over a lip.</p>
     *
     * <p>Gated on the ride's <i>share</i> of the press rather than on the raw press, which is what
     * keeps an ordinary floor - pressed at a full gravity by gravity - from becoming climbable. It
     * is not gated on the surface being a wall by world up, so it works just as well on the ceiling
     * at the top of a loop, which is exactly the ride this mod is for.</p>
     */
    private Vector3d surfaceClimb(
            final BodyFrame frame, final Vector3dc normal,
            final double press, final Vector3dc deckRelative) {

        final Vector3d result = new Vector3d();

        if (!CfConfig.SLIP_ENABLED.get() || !frame.contacts().any()) {
            return result;
        }

        final double gate = frame.spinGate();

        if (gate <= 0.0) {
            return result;
        }

        final double rideWeight = CfConfig.climbWeight(frame.frameShare());

        if (rideWeight <= 1.0e-3) {
            return result;
        }

        final double gravity = CfConfig.GRAVITY;

        final double share = CfConfig.smoothstep(press,
                CfConfig.GRIP_FULL_PRESS_G.get() * gravity,
                CfConfig.RIM_CLIMB_G.get() * gravity);

        if (share <= 1.0e-3) {
            return result;
        }

        // Gravity's pull along the surface - the thing that stops you walking up it.
        final Vector3d alongSurface = new Vector3d(0.0, -gravity, 0.0);
        final double into = alongSurface.dot(normal);

        alongSurface.sub(normal.x() * into, normal.y() * into, normal.z() * into);

        if (alongSurface.lengthSquared() < 1.0e-9) {
            return result;
        }

        // Do not keep helping once the player is already rising this fast along the surface.
        final Vector3d up = new Vector3d(alongSurface).negate().normalize();

        if (deckRelative.dot(up) >= CfConfig.RIM_CLIMB_SPEED.get()) {
            return result;
        }

        result.set(alongSurface).mul(-share * rideWeight * gate);
        STATE.climbAssist.set(result);

        return result;
    }

    /**
     * A small pull into the surface while latched.
     *
     * <p>Sable resolves entity/sub-level contact in a limited number of substeps, so a body exactly
     * touching a moving wall drifts a hair off it and back every tick - and "stuck to the drum"
     * becomes a coin flip that flickers. This keeps the contact closed so the state is stable.</p>
     *
     * <p>It is emphatically not what holds the player up; friction does that. Raising it does not
     * help you stick, it only makes letting go feel sticky.</p>
     */
    private Vector3d adhesion(final BodyFrame frame) {
        final Vector3d result = new Vector3d();

        if (!frame.isAttached()) {
            return result;
        }

        final double amount = CfConfig.ATTACH_ADHESION_G.get() * CfConfig.GRAVITY;

        if (amount <= 0.0) {
            return result;
        }

        return result.set(frame.attachNormal()).mul(-amount);
    }

    /**
     * The deck stopped; the player did not.
     *
     * <p>A lift carrying you up jams its edge on a frame. The sub-level halts in one tick, but you
     * were only ever attached to the deck, so the honest outcome is that you keep the velocity it
     * was giving you and continue - straight up, out through the middle.</p>
     *
     * <p>{@link BodyFrame} only ever raises the flag for a player who was already latched, which is
     * what keeps this from becoming "players ricochet off contraptions".</p>
     */
    private void launch(final LocalPlayer player, final BodyFrame frame) {
        final Vector3d velocity = new Vector3d(frame.releaseVelocity());

        if (!velocity.isFinite()) {
            return;
        }

        final double speed = velocity.length();

        // A speed rail, in m/s. The acceleration clamp is the wrong unit for this and was being
        // used here by mistake, which made it no clamp at all.
        if (speed > CfConfig.RELEASE_MAX_SPEED && speed > 1.0e-9) {
            velocity.mul(CfConfig.RELEASE_MAX_SPEED / speed);
        }

        final Vec3 own = player.getDeltaMovement();

        player.setDeltaMovement(
                own.x + velocity.x / 20.0,
                own.y + velocity.y / 20.0,
                own.z + velocity.z / 20.0);

        player.fallDistance = 0.0f;
    }
}
