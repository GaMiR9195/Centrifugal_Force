package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The physics tick. Runs once per client tick for the local player only.
 *
 * <h2>The model</h2>
 *
 * <p>Standing in a rotating frame, a body feels gravity plus three fictitious accelerations:
 * centrifugal {@code -w x (w x r)}, Euler {@code -a x r} and Coriolis {@code -2 w x v}. Add those
 * to gravity and you get <i>apparent gravity</i> - the direction the inner ear calls down. Every
 * single behaviour this mod is supposed to have falls out of that one vector:</p>
 *
 * <ul>
 *   <li>Spin a drum of radius r at w and the outward term is {@code w^2 * r}. Past 1 g it beats
 *       gravity, apparent down points at the wall, and the wall is now the floor. At 5 blocks that
 *       is about 2.5 rad/s, roughly 24 rpm - which is genuinely what a rotor ride spins at.</li>
 *   <li>Drag is {@code g * (v / reference)^2} against the player's speed <i>through the air</i>.
 *       On a spinner that is dominated by the deck's own tangential speed, so a fast ride is
 *       trying to peel you off even while you stand still.</li>
 *   <li>Friction holds up to {@code mu * normal_load} of tangential force and no more. Under the
 *       limit you stand; over it, only the excess moves you, so you slide slowly near the limit
 *       and fast well past it. Both terms grow with w, but drag grows as {@code (w*r)^2} while the
 *       press grows only as {@code w^2 * r} - so drag always wins eventually. Getting swept off a
 *       fast ride is not a special case in here, it is just the arithmetic.</li>
 * </ul>
 *
 * <h2>What is added to the player</h2>
 *
 * <p>Only the difference from what vanilla already does. Vanilla applies gravity every tick, so
 * adding apparent gravity outright would double it. On a level, non-spinning deck this handler
 * therefore contributes exactly zero and normal play is untouched.</p>
 */
public final class CentrifugalHandler {

    /** Last tick's result, for the camera and the overlay. */
    public static final ForceState STATE = new ForceState();

    /**
     * Candidate surface normals, in the sub-level's own local space.
     *
     * <p>Deliberately not a raycast. Hits on sub-level blocks come back in the sub-level's own
     * coordinate space - millions of blocks from the player - so every result would need converting
     * and disambiguating. A deck is block geometry, so its normal is always one of these six axes,
     * and picking the one most opposed to felt down is both cheaper and exact. It also makes the
     * floor-to-wall handover inside a drum free: as the spin builds, the winning axis just changes.</p>
     */
    private static final Vec3[] LOCAL_AXES = {
            new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, -1.0, 0.0),
            new Vec3(1.0, 0.0, 0.0), new Vec3(-1.0, 0.0, 0.0),
            new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, -1.0),
    };

    private final FrameSample frame = new FrameSample();

    private LocalPlayer owner;
    private boolean wasGripped;

    @SubscribeEvent
    public void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null || !CfConfig.SPEC.isLoaded()) {
            this.stop();
            this.owner = null;
            return;
        }

        // Respawn or dimension change hands us a different LocalPlayer object. The stored pose
        // delta belongs to the old one, and using it would kick the new one once.
        if (this.owner != player) {
            this.owner = player;
            this.stop();
        }

        if (!CfConfig.CENTRIFUGAL_ENABLED.get() && !CfConfig.AIR_ENABLED.get()) {
            this.stop();
            return;
        }

        final SubLevel subLevel = SableAccess.tracking(player);

        // isPassenger: sitting in a seat is Create's/Sable's problem, not ours - it already moves
        // you rigidly. flying/fallFlying/spectator: the player has explicitly opted out of footing.
        if (subLevel == null
                || subLevel.isRemoved()
                || subLevel.getLevel() != player.level()
                || player.isSpectator()
                || player.getAbilities().flying
                || player.isFallFlying()
                || player.isPassenger()) {
            this.stop();
            return;
        }

        this.frame.sample(subLevel);

        // First tick on this sub-level: there is no pose delta yet, so every rate would be a lie.
        if (!this.frame.isValid()) {
            STATE.clear();
            return;
        }

        this.apply(player, subLevel);
    }

    private void stop() {
        this.frame.reset();
        this.wasGripped = false;
        STATE.clear();
    }

    private void apply(final LocalPlayer player, final SubLevel subLevel) {
        final Pose3dc pose = subLevel.logicalPose();
        final Vec3 positionVec = player.position();
        final Vec3 deltaMovement = player.getDeltaMovement();

        // r is measured from the pose origin because that is the point Sable itself rotates about -
        // its own getVelocity() is w x (worldPos - pose.position()) + linear.
        final Vector3d radius = new Vector3d(positionVec.x, positionVec.y, positionVec.z)
                .sub(pose.position());

        final Vector3dc omega = this.frame.omega();

        final Vector3d gravity = new Vector3d(0.0, -CfConfig.GRAVITY, 0.0);

        // The player's velocity as measured in the rotating frame. deltaMovement is already
        // frame-relative: while you are tracked, Sable warps your position with the sub-level, so
        // the frame's own motion never appears in here. Times 20 to get m/s.
        final Vector3d relativeVelocity =
                new Vector3d(deltaMovement.x, deltaMovement.y, deltaMovement.z).mul(20.0);

        final Vector3d omegaCrossR = new Vector3d(omega).cross(radius, new Vector3d());
        final Vector3d centrifugal = new Vector3d(omega).cross(omegaCrossR, new Vector3d()).negate();
        final Vector3d euler = new Vector3d(this.frame.alpha()).cross(radius, new Vector3d()).negate();
        final Vector3d coriolis = new Vector3d(omega).cross(relativeVelocity, new Vector3d()).mul(-2.0);

        final Vector3d extra = new Vector3d();

        if (CfConfig.CENTRIFUGAL_ENABLED.get()) {
            extra.add(new Vector3d(centrifugal).mul(CfConfig.CENTRIFUGAL_STRENGTH.get()))
                    .add(new Vector3d(euler).mul(CfConfig.EULER_STRENGTH.get()))
                    .add(new Vector3d(coriolis).mul(CfConfig.CORIOLIS_STRENGTH.get()));
        }

        final Vector3d apparent = new Vector3d(gravity).add(extra);

        // Which way is down, as felt. Drag is excluded on purpose: wind should not be able to
        // convince you that a wall is the floor.
        final Vector3d down = new Vector3d(apparent);
        if (down.lengthSquared() < 1.0e-9) {
            down.set(0.0, -1.0, 0.0);
        } else {
            down.normalize();
        }

        final Vector3d normal = surfaceNormal(pose, down);

        // --- air ---

        final Vec3 deckVelocity = SableAccess.pointVelocity(subLevel, positionVec);
        final Vec3 wind = SableAccess.wind(player.level(), positionVec);

        final Vector3d airVelocity = new Vector3d(deckVelocity.x, deckVelocity.y, deckVelocity.z)
                .add(relativeVelocity)
                .sub(wind.x, wind.y, wind.z);

        final Vector3d drag = new Vector3d();
        final double airSpeed = airVelocity.length();

        if (CfConfig.AIR_ENABLED.get() && airSpeed > 1.0e-4 && Double.isFinite(airSpeed)) {
            final double reference = CfConfig.AIR_REFERENCE_SPEED.get();
            // Quadratic, and normalised so that |a| == gravity exactly at v == reference. That is
            // what makes the knob a speed you can picture instead of a multiplier you cannot.
            final double magnitude = CfConfig.GRAVITY * (airSpeed * airSpeed) / (reference * reference);
            drag.set(airVelocity).div(airSpeed).mul(-magnitude);
        }

        // --- footing ---

        final Vector3d extraTotal = new Vector3d(extra).add(drag);
        final Vector3d total = new Vector3d(gravity).add(extraTotal);

        final double press = -total.dot(normal);

        // Cheap contact test. Sable does keep a real contact manifold, but only behind
        // EntityMovementExtension#sable$getCollisionInfo(), which is internal - see docs/UPSTREAM.md.
        // These three flags are the same signals Sure Footing trusts.
        final boolean touching = player.onGround() || player.verticalCollision || player.horizontalCollision;
        final boolean gripped = touching && press >= CfConfig.GRIP_MIN_PRESS_G.get() * CfConfig.GRAVITY;

        final boolean bracing = player.isShiftKeyDown();
        final double friction = CfConfig.GRIP_FRICTION.get()
                * (bracing ? CfConfig.GRIP_BRACE_BONUS.get() : 1.0);
        final double hold = friction * Math.max(0.0, press);

        final Vector3d tangential = new Vector3d(total)
                .sub(new Vector3d(normal).mul(total.dot(normal)));
        final double tangentialLoad = tangential.length();

        final Vector3d applied = new Vector3d();
        boolean slipping;

        if (gripped) {
            final double surviving = Math.max(0.0, tangentialLoad - hold);
            slipping = surviving > 1.0e-6;

            final Vector3d slide = new Vector3d();
            if (tangentialLoad > 1.0e-9) {
                slide.set(tangential).div(tangentialLoad).mul(surviving);
            }

            // Vanilla is already pushing you down the slope with gravity's tangential share, so to
            // end up with only `slide` we have to add the difference. When friction wins that
            // difference is negative - and a negative addition IS the friction. This is also why a
            // merely tilted deck stops sliding you at all: g*(sin - mu*cos) is exactly zero there.
            final Vector3d tangentialGravity = new Vector3d(gravity)
                    .sub(new Vector3d(normal).mul(gravity.dot(normal)));

            applied.set(normal).mul(extraTotal.dot(normal))
                    .add(slide)
                    .sub(tangentialGravity);
        } else {
            // Nothing is holding you. Everything applies, which is what makes the arc off a
            // spinner look right.
            applied.set(extraTotal);
            slipping = true;
        }

        final double limit = CfConfig.MAX_ACCEL_G.get() * CfConfig.GRAVITY;
        final double magnitude = applied.length();

        if (!applied.isFinite()) {
            applied.zero();
        } else if (magnitude > limit && magnitude > 1.0e-9) {
            applied.mul(limit / magnitude);
        }

        // a m/s^2 for one tick is a/20 m/s, which is a/400 blocks/tick.
        if (applied.lengthSquared() > 1.0e-12) {
            player.setDeltaMovement(deltaMovement.add(
                    applied.x / 400.0, applied.y / 400.0, applied.z / 400.0));
        }

        // Being pressed into a wall at several g racks up fall distance you did not earn. Note this
        // is only the client's counter: the server keeps its own from position deltas, so this
        // softens the problem rather than solving it. See README.
        if (gripped && press > 1.5 * CfConfig.GRAVITY) {
            player.resetFallDistance();
        }

        if (CfConfig.RELEASE_TRACKING.get()
                && this.wasGripped
                && !gripped
                && deckVelocity.length() >= CfConfig.RELEASE_SPEED.get()) {
            // Off by default. Sable already hands over an inherited velocity when tracking ends -
            // that is why we do NOT add deckVelocity by hand here; doing both would count the
            // deck's momentum twice. All this does is end the tracking sooner.
            SableAccess.setTracking(player, null);
        }

        this.wasGripped = gripped;

        STATE.active = true;
        STATE.centrifugal.set(centrifugal);
        STATE.euler.set(euler);
        STATE.coriolis.set(coriolis);
        STATE.drag.set(drag);
        STATE.apparent.set(apparent);
        STATE.applied.set(applied);
        STATE.normal.set(normal);
        STATE.airVelocity.set(airVelocity);
        STATE.deckVelocity.set(deckVelocity.x, deckVelocity.y, deckVelocity.z);
        STATE.omega.set(omega);
        STATE.press = press;
        STATE.hold = hold;
        STATE.tangentialLoad = tangentialLoad;
        STATE.gripped = gripped;
        STATE.slipping = slipping;
        STATE.bracing = bracing;
    }

    /** The sub-level face most opposed to felt down, as a unit world-space normal. */
    private static Vector3d surfaceNormal(final Pose3dc pose, final Vector3dc down) {
        final Vector3d best = new Vector3d(0.0, 1.0, 0.0);
        double bestDot = -Double.MAX_VALUE;

        for (final Vec3 axis : LOCAL_AXES) {
            final Vec3 world = pose.transformNormal(axis);
            final double length = world.length();

            if (length < 1.0e-9) {
                continue;
            }

            // transformNormal carries the sub-level's scale, so normalise before comparing.
            final double dot = -(world.x * down.x() + world.y * down.y() + world.z * down.z()) / length;

            if (dot > bestDot) {
                bestDot = dot;
                best.set(world.x / length, world.y / length, world.z / length);
            }
        }

        return best;
    }
}
