package com.playsi.centrifugal_force.internal;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Drives adhesion for the local player.
 *
 * <p>Every decision is taken in the pre-tick, before movement runs: the orientation for the tick is
 * committed, the box centre is placed so the box stays tangent to the planes it rests on, and the
 * frame carry Sable applies later in the tick is reduced to pure sub-level motion. Nothing rotates
 * or teleports while collisions are being resolved.
 */
public final class AdhesionEngine {
    public static final AdhesionEngine INSTANCE = new AdhesionEngine();

    private static final int ALIGN_TICKS = 4;
    private static final int ROLL_TICKS = 10;
    private static final int RELEASE_TICKS = 4;

    /** How close a plane has to be for the box to count as resting on it. */
    private static final double CONTACT_GAP = 0.12;
    /** Adhesion has to survive a jump, so support is looked for well past the box. */
    private static final double SUPPORT_REACH = 1.4;
    private static final double ATTACH_REACH = 0.4;
    private static final double MIN_PRESS = 1.0e-3;
    private static final double MIN_UPWARD = 0.05;
    /** Vertical drag LivingEntity#travel applies right after gravity. */
    private static final double VERTICAL_DRAG = 0.98;
    private static final double[] ARC_SAMPLES = {0.35, 0.7, 1.0};
    private static final double ARC_SKIN = 0.02;

    private static final Quaterniondc IDENTITY = new Quaterniond();

    private AdhesionEngine() {}

    @SubscribeEvent
    public void onPreTick(final EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof final Player player) || !player.isLocalPlayer()) return;

        final AdhesionState state = ((AdhesionAccess) player).centrifugalForce$getOrCreateAdhesionState();
        update(player, state);
        state.beginPhysicsTick();
    }

    @SubscribeEvent
    public void onPostTick(final EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof final Player player) || !player.isLocalPlayer()) return;

        final AdhesionState state = ((AdhesionAccess) player).centrifugalForce$peekAdhesionState();
        if (state == null) return;
        state.endPhysicsTick();
        if (state.isActive()) redirectGravity(player, state);
    }

    private void update(final Player player, final AdhesionState state) {
        if (!state.isActive() && !attach(player, state)) return;

        final SubLevel subLevel = state.subLevel();
        if (subLevel == null || subLevel.isRemoved() || subLevel.getLevel() != player.level()
                || player.isSpectator() || player.isPassenger()) {
            state.clear();
            return;
        }

        final Pose3dc pose = subLevel.logicalPose();
        final Hitbox hitbox = new Hitbox(player).orient(state.localOrientation());
        final Vector3d centre = hitbox.localCentre(player.position(), state.currentOrientation(), pose, new Vector3d());

        final boolean grounded = updateSupport(player, state, subLevel, hitbox, centre);
        state.setGrounded(grounded);
        if (grounded && !state.isChanging()) tryRoll(player, state, subLevel, pose, hitbox, centre);

        final Transition.Contact[] contacts = state.contacts(grounded);
        final boolean ended = state.advance();

        hitbox.orient(state.localOrientation());
        for (final Transition.Contact contact : contacts) {
            hitbox.rest(centre, contact.normal(), contact.plane());
        }

        final Quaterniond orientation = new Quaterniond(pose.orientation()).mul(state.localOrientation());
        final Vec3 position = hitbox.position(centre, orientation, pose);
        player.setPos(position.x, position.y, position.z);

        state.commit(orientation);
        state.setFrameCarry(frameCarry(subLevel, hitbox.feet(position, orientation)));
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(subLevel);
        if (ended) state.clear();
    }

    /** Starts adhesion on the face the player is standing on. */
    private boolean attach(final Player player, final AdhesionState state) {
        if (player.isSpectator() || player.isPassenger()) return false;

        final SubLevel subLevel = Sable.HELPER.getTrackingSubLevel(player);
        if (subLevel == null || subLevel.isRemoved() || subLevel.getLevel() != player.level()) return false;

        final Pose3dc pose = subLevel.logicalPose();
        final Quaterniond upright = new Quaterniond(pose.orientation()).invert();
        final Hitbox hitbox = new Hitbox(player).orient(upright);
        final Vector3d centre = hitbox.localCentre(player.position(), IDENTITY, pose, new Vector3d());
        final Vector3d down = pose.transformNormalInverse(new Vector3d(0.0, -1.0, 0.0), new Vector3d()).normalize();

        final SurfaceProbe.Face face = SurfaceProbe.cast(player, subLevel, centre, down,
                hitbox.extent(down) + ATTACH_REACH);
        if (face == null) return false;

        final Vector3d up = pose.transformNormal(face.normal(), new Vector3d()).normalize();
        if (up.y < MIN_UPWARD) return false;

        final Transition.Contact contact = new Transition.Contact(face.normal(), face.plane());
        state.attach(subLevel, face.normal(), face.plane(), upright);
        state.begin(new Transition(upright,
                new Quaterniond(upright).mul(Rotations.swing(Rotations.UP, up, new Quaterniond())),
                ALIGN_TICKS, new Transition.Contact[]{contact}, contact));
        return true;
    }

    /** Refreshes the support plane and reports whether the box is resting on it. */
    private boolean updateSupport(final Player player, final AdhesionState state, final SubLevel subLevel,
                                  final Hitbox hitbox, final Vector3dc centre) {
        final Vector3dc normal = state.support();
        final double extent = hitbox.extent(normal);
        final SurfaceProbe.Face face = SurfaceProbe.cast(player, subLevel, centre,
                new Vector3d(normal).negate(), extent + SUPPORT_REACH);

        if (face == null || face.normal().dot(normal) < 0.9) {
            if (!state.isChanging()) release(state, subLevel);
            return false;
        }
        state.setSupportPlane(face.plane());
        return hitbox.gap(centre, normal, face.plane()) <= CONTACT_GAP;
    }

    /**
     * Rolls onto the plane the player is pushing into. Symmetric by construction: the current
     * support is the only excluded axis, so a wall leads back to the floor exactly like the floor
     * leads to the wall.
     */
    private void tryRoll(final Player player, final AdhesionState state, final SubLevel subLevel,
                         final Pose3dc pose, final Hitbox hitbox, final Vector3dc centre) {
        final SubLevelEntityCollision.CollisionInfo info = ((EntityMovementExtension) player).sable$getCollisionInfo();
        if (info == null || info.preDeltaMovement == null) return;

        final Vector3d press = pose.transformNormalInverse(new Vector3d(info.preDeltaMovement.x,
                info.preDeltaMovement.y, info.preDeltaMovement.z), new Vector3d());
        final Vector3dc support = state.support();

        Vector3dc target = null;
        double targetPlane = 0.0;
        double best = MIN_PRESS;
        for (final Vector3dc axis : Rotations.AXES) {
            if (Math.abs(axis.dot(support)) > 0.5 || -press.dot(axis) <= best) continue;

            final SurfaceProbe.Face face = SurfaceProbe.cast(player, subLevel, centre,
                    new Vector3d(axis).negate(), hitbox.extent(axis) + CONTACT_GAP);
            if (face == null || face.normal().dot(axis) < 0.9) continue;

            target = axis;
            targetPlane = face.plane();
            best = -press.dot(axis);
        }
        if (target == null) return;

        final Transition roll = Transition.roll(state.localOrientation(), support, state.supportPlane(),
                target, targetPlane, ROLL_TICKS);
        if (arcIsClear(player, subLevel, hitbox, roll, centre)) state.begin(roll);
    }

    /** Ramps back to the sub-level's own orientation, then adhesion ends. */
    private void release(final AdhesionState state, final SubLevel subLevel) {
        state.begin(new Transition(state.localOrientation(),
                new Quaterniond(subLevel.logicalPose().orientation()).invert(), RELEASE_TICKS,
                Transition.NONE, null));
    }

    /** The box has to fit through the whole rotation, not only at its ends. */
    private boolean arcIsClear(final Player player, final SubLevel subLevel, final Hitbox hitbox,
                               final Transition roll, final Vector3dc centre) {
        final Quaterniond sample = new Quaterniond();
        final Vector3d swept = new Vector3d();
        final Vector3d[] corners = new Vector3d[8];
        for (int corner = 0; corner < corners.length; corner++) corners[corner] = new Vector3d();

        for (final double progress : ARC_SAMPLES) {
            hitbox.orient(roll.orientationAt(progress, sample));
            swept.set(centre);
            for (final Transition.Contact contact : roll.contacts()) {
                hitbox.rest(swept, contact.normal(), contact.plane());
            }
            hitbox.corners(swept, ARC_SKIN, corners);
            if (!SurfaceProbe.clear(player, subLevel, corners)) return false;
        }
        return true;
    }

    /** Pure sub-level motion of the contact anchor between the last pose and the current one. */
    private @Nullable Vec3 frameCarry(final SubLevel subLevel, final Vec3 anchor) {
        final Vector3d local = subLevel.lastPose().transformPositionInverse(
                new Vector3d(anchor.x, anchor.y, anchor.z), new Vector3d());
        subLevel.logicalPose().transformPosition(local, local);
        final Vec3 carry = new Vec3(local.x - anchor.x, local.y - anchor.y, local.z - anchor.z);
        return carry.lengthSqr() < 1.0e-8 ? null : carry;
    }

    /**
     * Undoes the vanilla vertical pull and applies the same magnitude along the local down axis, so
     * a tilted or vertical plane holds the player instead of sliding them off it.
     */
    private void redirectGravity(final Player player, final AdhesionState state) {
        if (player.getAbilities().flying || player.isFallFlying() || player.isInWater() || player.isInLava()
                || player.hasEffect(MobEffects.LEVITATION)) {
            return;
        }

        final Vector3d up = state.currentOrientation().transform(new Vector3d(Rotations.UP));
        final double pull = player.getAttributeValue(Attributes.GRAVITY) * VERTICAL_DRAG;
        if (pull == 0.0 || up.y > 1.0 - 1.0e-9) return;

        player.setDeltaMovement(player.getDeltaMovement()
                .add(0.0, pull, 0.0)
                .subtract(up.x * pull, up.y * pull, up.z * pull));
    }
}
