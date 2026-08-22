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
 * deliberate gameplay pushes (the outward creep and the wall assist). Gravity is never applied here
 * - vanilla already does that. Getting it wrong doubles gravity, and it is why {@code apparent}
 * exists purely as a reported quantity rather than as something summed into the player.</p>
 *
 * <p>The consequence worth stating plainly: on a sub-level that is merely travelling - a lift, a
 * ship under way, a drawbridge - every one of those terms is zero, so this handler adds exactly
 * nothing and movement is bit-for-bit vanilla. Not a tuned threshold; what the terms evaluate to.
 * </p>
 *
 * <h2>A slide has to have a terminal speed</h2>
 *
 * <p>Coulomb friction is one comparison - friction holds up to {@code mu * press} and only the
 * excess moves you - and that is still the model here. But an excess applied every tick with
 * nothing watching the result is not a slide, it is an engine: a single hard turn started a slide
 * that never stopped, and the only way to make it stop was to raise {@code grip} until the gate
 * never opened at all. That is what "grip 4 and it stopped shoving me" was really reporting.</p>
 *
 * <p>Two bounds fix it without adding a state machine. The excess is capped in <i>acceleration</i>
 * by {@code slide_cap_g}, so a violent ride shoves rather than deletes; and it fades against the
 * measured deck-relative speed, so the slide approaches a terminal drift. Both are limits on the
 * push, never a brake on the player - a brake would fight walking, and the player walking is not
 * something this mod is entitled to oppose.</p>
 *
 * <h2>Velocity across the deck is measured, not inferred</h2>
 *
 * <p>Sable carries a standing player by moving their <i>position</i> through the pose, and never
 * touches {@code deltaMovement}. So {@code deltaMovement} is blind to sliding across the deck: a
 * player dragged outward at three metres a second reports zero. Every speed limit here reads
 * {@link BodyFrame#deckRelativeVelocity()}, which is differenced from the player's local position
 * and therefore sees what is actually happening. A limiter that cannot observe the quantity it
 * limits is not a limiter.</p>
 *
 * <h2>Drag is measured against the air the deck carries</h2>
 *
 * <p>The rigid translation is subtracted first. Feeding a player's world velocity to a drag law
 * tells someone standing still on a deck cruising at 25 m/s that they are in a 25 m/s gale. What
 * survives is exactly what should: rotation does not move the pose's rotation centre, so
 * {@code omega x r} is untouched and a spinner still tries to peel you off, while pure translation
 * cancels to zero identically.</p>
 */
public final class CentrifugalHandler {

    /**
     * Acceleration in m/s^2 to a velocity delta in blocks/tick: {@code a/20} m/s over one tick,
     * divided by 20 again to get blocks/tick. Forgetting the second division is a factor-of-twenty
     * error that looks like the mod working perfectly on one machine.
     */
    public static final double ACCEL_TO_DELTA = 1.0 / 400.0;

    /** Above this press, in g, fall damage is suppressed: you are being held, not falling. */
    private static final double FALL_RESET_G = 1.5;

    /** Below this walk-assist fade the keys are not re-based; only gravity is cancelled. */
    private static final double WALK_ASSIST_MIN = 0.35;

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

        final Vec3 ownVec = player.getDeltaMovement().scale(20.0);
        final Vector3d own = new Vector3d(ownVec.x, ownVec.y, ownVec.z);

        if (!own.isFinite()) {
            own.zero();
        }

        final Vec3 windVec = SableAccess.wind(player.level(), position);

        // deck - translation is exactly the rotational part, omega x r.
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

        // The camera's target. Derived from the same body orientation the hitbox follows, so the
        // two cannot drift into "the view leans one way and the body is somewhere else".
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

        // Jumping always breaks a latch. Being stuck to a wall with no way off is a trap, and
        // pressing jump is the least ambiguous "let go" signal there is.
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
            // -2 omega x v_rel. Only the player's own motion counts: the deck's velocity is already
            // in the frame acceleration, and double counting turns a turntable into a catapult.
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

        final Vector3d load = new Vector3d(rideLoad).add(coriolis).add(drag);

        // Total normal load includes gravity: friction does not care where the press came from, it
        // is what is squeezing your boots against the surface.
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

        applied.add(this.pressInto(normal, normalComponent, deckRelative));
        applied.add(this.slide(tangential, tangentialLoad, hold, deckRelative));

        // ------------------------------------------------------------ deliberate pushes

        applied.add(this.outwardCreep(frame, rideLoad, normal, deckRelative));
        applied.add(this.wallAssist(player, frame, normal, press, deckRelative));
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

        // Being pressed into a drum wall is not falling, whatever the vertical delta says. The
        // server keeps its own fall counter from position deltas, so this only fixes the client's
        // opinion - use /gamerule fallDamage false while testing rides.
        if (press > FALL_RESET_G * gravity) {
            player.fallDistance = 0.0f;
        }
    }

    /**
     * The component that pins you to the surface, faded out once you are already closing on it.
     *
     * <p>This used to be applied in full, unconditionally, on the reasoning that collision would
     * eat whatever was pointed into the surface. It does not. Sable resolves contact
     * <i>positionally</i> and never zeroes {@code deltaMovement} against a sub-level face, so the
     * into-surface velocity is kept and added to every tick. Nothing looks wrong while it builds,
     * because the position is being corrected each tick anyway - and then one tick resolves the
     * accumulated penetration all at once and the player is fired through the wall.</p>
     *
     * <p>Pushing away from the surface is never faded: that is you leaving, and you are allowed to
     * leave.</p>
     */
    private Vector3d pressInto(final Vector3dc normal, final double normalComponent,
                               final Vector3dc deckRelative) {

        final Vector3d result = new Vector3d(normal).mul(normalComponent);

        if (normalComponent >= 0.0) {
            return result;
        }

        final double limit = CfConfig.PRESS_MAX_SPEED;

        if (!(limit > 0.0)) {
            return result;
        }

        // Positive when the player is closing on the surface.
        final double approach = -deckRelative.dot(normal);
        final double room = Math.min(1.0, Math.max(0.0, (limit - approach) / limit));

        return result.mul(room);
    }

    /**
     * The excess past friction, bounded twice.
     *
     * <p>{@code slide_cap_g} bounds the acceleration, so a contraption snapping sideways shoves you
     * rather than evicting you. The speed fade bounds the outcome, so the slide settles at a
     * terminal drift instead of accelerating for as long as the ride keeps turning.</p>
     *
     * <p>Both are limits on the push and neither is a brake on the player. That distinction is
     * deliberate: a brake would have to oppose the deck-relative velocity, which contains the
     * player's own walking, and opposing the player walking is not something this mod is entitled
     * to do.</p>
     */
    private Vector3d slide(final Vector3dc tangential, final double tangentialLoad,
                           final double hold, final Vector3dc deckRelative) {

        final Vector3d result = new Vector3d();

        if (tangentialLoad <= 1.0e-9 || tangentialLoad <= hold) {
            return result;
        }

        final Vector3d direction = new Vector3d(tangential).div(tangentialLoad);

        double excess = tangentialLoad - hold;

        final double accelCap = CfConfig.GRIP_SLIDE_CAP_G.get() * CfConfig.GRAVITY;

        if (excess > accelCap) {
            excess = accelCap;
        }

        final double limit = CfConfig.AIR_SLIDE_MAX_SPEED.get();

        if (limit > 0.0) {
            final double already = deckRelative.dot(direction);

            excess *= Math.min(1.0, Math.max(0.0, (limit - already) / limit));
        }

        if (excess <= 1.0e-9) {
            return result;
        }

        STATE.slipping = STATE.gripped;
        STATE.slip.set(direction).mul(excess / 20.0);

        return result.set(direction).mul(excess);
    }

    /**
     * The deliberate outward creep - now part of {@code air_resistance}, not a knob of its own.
     *
     * <p>In a drum you should ease from the middle out to the rim and end up leaning on the lip.
     * Friction alone will not do it: friction is symmetric, so once it holds you it holds you
     * exactly where you are and the ride never moves you anywhere.</p>
     *
     * <p>It lives under air resistance because it is the same idea - the ride peeling you off - and
     * having it under a second switch meant turning air resistance off did not stop you sliding,
     * which is not a thing anyone could have guessed from the name.</p>
     */
    private Vector3d outwardCreep(
            final BodyFrame frame, final Vector3dc rideLoad,
            final Vector3dc normal, final Vector3dc deckRelative) {

        final Vector3d result = new Vector3d();

        if (!CfConfig.AIR_ENABLED.get() || !frame.contacts().any()) {
            return result;
        }

        final double gate = frame.spinGate();
        final double strength = CfConfig.AIR_SLIDE.get() * CfConfig.AIR_STRENGTH.get();

        if (gate <= 0.0 || strength <= 0.0) {
            return result;
        }

        // Outward is where the ride is throwing you, projected onto the surface: sliding happens
        // along a surface, and the part pointing into it is press, not creep.
        final Vector3d outward = new Vector3d(rideLoad);
        final double intoSurface = outward.dot(normal);

        outward.sub(normal.x() * intoSurface, normal.y() * intoSurface, normal.z() * intoSurface);

        final double magnitude = outward.length();

        if (magnitude < 1.0e-6) {
            return result;
        }

        outward.div(magnitude);

        final double limit = CfConfig.AIR_SLIDE_MAX_SPEED.get();

        if (!(limit > 0.0)) {
            return result;
        }

        // Fades to nothing at the cap, so the creep approaches a terminal drift rather than being
        // cut off at one.
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
     * Wall walking, in two halves.
     *
     * <p><b>Half one</b> cancels a share of the along-surface pull of gravity, in proportion to how
     * hard the ride is pressing you in. That is the honest model: a rider in a real rotor walks up
     * the drum because centrifugal press gives their boots enough friction to beat gravity along
     * the wall - not because the world rotated for them, and not because they are glued on.</p>
     *
     * <p><b>Half two</b> re-bases the walk keys into the surface plane, and it is the half that was
     * missing. Cancelling gravity only ever bought the right to <i>hover</i>: you press W,
     * Minecraft resolves your input in the horizontal world plane, pushes you straight into the
     * wall, and nothing happens. Forward has to mean "forward along the surface" before a wall can
     * be walked on, and no amount of force in the other half produces that.</p>
     *
     * <p>Gated on the ride's <i>share</i> of the press rather than on raw press, which is what
     * keeps an ordinary floor - pressed at a full gravity, by gravity - from becoming climbable.
     * Not gated on the surface being a wall by world up, so it works just as well on what is over
     * your head at the top of a loop, which is exactly the ride this mod is for.</p>
     */
    private Vector3d wallAssist(final LocalPlayer player, final BodyFrame frame,
                                final Vector3dc normal, final double press,
                                final Vector3dc deckRelative) {

        final Vector3d result = new Vector3d();

        if (!CfConfig.WALL_ENABLED.get() || !frame.contacts().any()) {
            return result;
        }

        final double gate = frame.spinGate() * CfConfig.climbWeight(frame.frameShare());

        if (gate <= 1.0e-3) {
            return result;
        }

        final double gravity = CfConfig.GRAVITY;

        final double ramp = CfConfig.smoothstep(press,
                CfConfig.GRIP_FULL_PRESS_G.get() * gravity,
                CfConfig.WALL_PRESS_G.get() * gravity);

        if (ramp <= 1.0e-3) {
            return result;
        }

        final double amount = Math.min(1.0, ramp * gate) * CfConfig.WALL_STRENGTH.get();

        if (amount <= 1.0e-3) {
            return result;
        }

        // Gravity's pull along the surface - the thing that stops you walking up it.
        final Vector3d alongSurface = new Vector3d(0.0, -gravity, 0.0);
        final double into = alongSurface.dot(normal);

        alongSurface.sub(normal.x() * into, normal.y() * into, normal.z() * into);

        if (alongSurface.lengthSquared() > 1.0e-9) {
            result.fma(-Math.min(1.0, amount), alongSurface);
        }

        if (amount >= WALK_ASSIST_MIN) {
            final Vector3d desired = walkDirection(player, normal);

            if (desired.lengthSquared() > 1.0e-6) {
                final double limit = CfConfig.WALL_MAX_SPEED.get();

                final double already = deckRelative.dot(desired);
                final double room = limit > 0.0
                        ? Math.min(1.0, Math.max(0.0, (limit - already) / limit))
                        : 0.0;

                if (room > 0.0) {
                    result.fma(CfConfig.WALL_WALK_ACCEL * amount * room, desired);
                }
            }
        }

        STATE.climbAssist.set(result);

        return result;
    }

    /**
     * The walk keys, expressed as a unit direction in the surface plane.
     *
     * <p>Forward is where you are looking, flattened onto the surface rather than onto the world's
     * horizontal plane - so on a drum wall "forward" is up the wall when you look up it. Left is
     * {@code normal x forward}, which reduces to the vanilla left when the normal is world up, so
     * nothing changes on an ordinary floor.</p>
     */
    private static Vector3d walkDirection(final LocalPlayer player, final Vector3dc normal) {
        final Vector3d out = new Vector3d();

        if (player.input == null) {
            return out;
        }

        final double forwardImpulse = player.input.forwardImpulse;
        final double leftImpulse = player.input.leftImpulse;

        if (Math.abs(forwardImpulse) < 1.0e-3 && Math.abs(leftImpulse) < 1.0e-3) {
            return out;
        }

        final Vec3 look = player.getLookAngle();
        final Vector3d forward = new Vector3d(look.x, look.y, look.z);

        projectOntoPlane(forward, normal);

        if (forward.lengthSquared() < 1.0e-6) {
            // Looking straight at the surface leaves nothing to project. Fall back to the body's
            // yaw, which still has a component in the plane.
            final double yaw = Math.toRadians(player.getYRot());

            forward.set(-Math.sin(yaw), 0.0, Math.cos(yaw));
            projectOntoPlane(forward, normal);

            if (forward.lengthSquared() < 1.0e-6) {
                return out;
            }
        }

        forward.normalize();

        final Vector3d left = new Vector3d(normal).cross(forward);

        if (left.lengthSquared() < 1.0e-9) {
            return out;
        }

        left.normalize();

        out.set(forward).mul(forwardImpulse).fma(leftImpulse, left);

        final double length = out.length();

        if (length < 1.0e-6 || !Double.isFinite(length)) {
            out.zero();
            return out;
        }

        return out.div(length);
    }

    private static void projectOntoPlane(final Vector3d vector, final Vector3dc normal) {
        final double along = vector.dot(normal);

        vector.sub(normal.x() * along, normal.y() * along, normal.z() * along);

        if (!vector.isFinite()) {
            vector.zero();
        }
    }

    /**
     * Splits the ride load into a centrifugal part and everything else, for display only.
     *
     * <p>Nothing downstream of the debug overlay depends on this being exact - the physics uses the
     * total, which is the point of taking it from Sable's velocity field rather than assembling it
     * by hand.</p>
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

        final double alongAxis = load.dot(axis);
        final Vector3d perpendicular = new Vector3d(load)
                .sub(axis.x * alongAxis, axis.y * alongAxis, axis.z * alongAxis);

        STATE.centrifugal.set(perpendicular);
        STATE.euler.set(load).sub(perpendicular);
    }

    /**
     * A small pull into the surface while latched.
     *
     * <p>Sable resolves contact in a limited number of substeps, so a body exactly touching a
     * moving wall drifts a hair off it and back every tick, and "stuck to the drum" becomes a coin
     * flip. This keeps the contact closed so the state is stable. It is emphatically not what holds
     * the player up - friction does that. Raising it does not help you stick, it only makes letting
     * go feel sticky.</p>
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
     * <p>{@link BodyFrame} only ever raises the flag for a player who was already latched, which is
     * what keeps this from becoming "players ricochet off contraptions".</p>
     */
    private void launch(final LocalPlayer player, final BodyFrame frame) {
        final Vector3d velocity = new Vector3d(frame.releaseVelocity());

        if (!velocity.isFinite()) {
            return;
        }

        final double speed = velocity.length();

        // A speed rail, in m/s. The acceleration clamp is the wrong unit for this.
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
