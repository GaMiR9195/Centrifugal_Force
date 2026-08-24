package com.playsi.centrifugal_force.internal;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Drives adhesion for every player. Each step is a separate static method so it can be reused or
 * replaced from other code without touching the tick wiring.
 */
public final class AdhesionEngine {
    public static final AdhesionEngine INSTANCE = new AdhesionEngine();

    private AdhesionEngine() {}

    @SubscribeEvent
    public void onEntityTickPre(final EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof final Player player)) return;

        AdhesionState state = peekState(player);
        if (state != null) {
            state.endPhysicsTick();
            state.tickReattachCooldown();
        }

        if (!eligible(player)) {
            if (state != null && state.isActive()) state.detach();
            return;
        }

        if (state == null || !state.isActive()) {
            final SubLevel candidate = findCandidate(player);
            if (candidate == null) return;
            if (state == null) state = createState(player);
            if (!state.canAttach() || !tryAttach(player, state, candidate)) return;
        }

        final SubLevel subLevel = state.subLevel();
        if (subLevel == null || subLevel.getLevel() != player.level()) {
            state.detach();
            return;
        }

        // Keep Sable's own tracking pointed at this sub-level so deck carrying and Sure Footing
        // keep working while standing on a wall.
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(subLevel);

        // Must happen before anything in this tick can collide.
        state.beginPhysicsTick();
        state.advanceTransition();

        if (state.isChangingPlane() && ownsMovement(player)) {
            // While rounding a corner the arc is the only thing allowed to move the player, so
            // nothing can push the hitbox into the geometry or fling it away.
            applyCornerPosition(player, state, subLevel);
            player.setDeltaMovement(Vec3.ZERO);
            clearInheritedVelocity(player);
            player.resetFallDistance();
        }
    }

    @SubscribeEvent
    public void onEntityTickPost(final EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof final Player player)) return;

        final AdhesionState state = peekState(player);
        if (state == null) return;
        state.endPhysicsTick();
        if (!state.isActive()) return;

        final SubLevel subLevel = state.subLevel();
        if (subLevel == null || !eligible(player) || subLevel.getLevel() != player.level()) {
            state.detach();
            return;
        }

        final EntityMovementExtension movement = (EntityMovementExtension) player;

        if (state.isChangingPlane()) {
            // Any leftover inherited velocity here can only have come from the orientation step
            // itself, so it is dropped instead of being allowed to build up.
            clearInheritedVelocity(player);
            player.resetFallDistance();
            state.finishTransitionIfComplete();
            movement.sable$setTrackingSubLevel(subLevel);
            return;
        }
        state.finishTransitionIfComplete();

        if (ownsMovement(player)) {
            tryPlaneChange(player, state, subLevel, movement.sable$getCollisionInfo());
            applyLocalGravity(player, state);
        }

        updateSupport(player, state, subLevel, movement.sable$getCollisionInfo());
        if (state.isActive()) movement.sable$setTrackingSubLevel(subLevel);
    }

    /** Finds a sub-level worth testing, without requiring the player to already stand on one. */
    public static @Nullable SubLevel findCandidate(final Player player) {
        final SubLevel tracked = Sable.HELPER.getTrackingSubLevel(player);
        if (tracked != null && !tracked.isRemoved()) return tracked;

        final SubLevel containing = Sable.HELPER.getContaining(player);
        if (containing != null && !containing.isRemoved()) return containing;

        // A slope steep enough to slide off never becomes a tracked floor, so the search cannot
        // depend on tracking at all.
        final BoundingBox3d bounds = new BoundingBox3d(player.getBoundingBox().inflate(1.0));
        for (final SubLevel subLevel : Sable.HELPER.getAllIntersecting(player.level(), bounds)) {
            if (!subLevel.isRemoved()) return subLevel;
        }
        return null;
    }

    /**
     * Attaches to whichever of the six local faces is under the player's pivot.
     *
     * <p>The probe runs in the sub-level's own space, so how steep the surface is in world terms
     * is irrelevant: a deck tilted past the point where vanilla would let anyone stand still has
     * a local face right under the pivot, and that is what gets picked.
     */
    public static boolean tryAttach(final Player player, final AdhesionState state, final SubLevel subLevel) {
        final Pose3dc pose = subLevel.logicalPose();
        final Vector3d pivotLocal = SurfaceProbe.localPivot(player, pose, new Vector3d());
        final Vector3d localWorldUp = pose.transformNormalInverse(new Vector3d(AdhesionMath.UP), new Vector3d());
        if (localWorldUp.lengthSquared() < 1.0e-9) return false;
        localWorldUp.normalize();

        final double eyeHeight = player.getEyeHeight();
        Vector3dc best = null;
        double bestScore = -Double.MAX_VALUE;

        for (final Vector3dc axis : AdhesionMath.AXES) {
            final SurfaceProbe.Hit hit = SurfaceProbe.probe(player, subLevel, pivotLocal, axis,
                    eyeHeight + AdhesionSettings.ATTACH_REACH);
            if (hit == null) continue;
            final double gap = hit.distance() - eyeHeight;
            if (gap < -AdhesionSettings.ATTACH_SINK_TOLERANCE || gap > AdhesionSettings.ATTACH_REACH) continue;
            final double score = axis.dot(localWorldUp) * 0.35 - Math.abs(gap);
            if (score > bestScore) {
                bestScore = score;
                best = axis;
            }
        }

        if (best == null) return false;
        state.attach(subLevel, best, localWorldUp);
        return true;
    }

    /**
     * Starts a plane change when the hitbox touches a face perpendicular to the current one.
     *
     * <p>There is no limit on the accumulated angle, so floor to wall to ceiling to wall and back
     * to the floor all work, in either direction. Touching a perpendicular face with the head is
     * the same event as touching one with the side, so that flips the player too.
     */
    public static boolean tryPlaneChange(final Player player, final AdhesionState state, final SubLevel subLevel,
                                         final @Nullable SubLevelEntityCollision.CollisionInfo collision) {
        if (collision == null || collision.firstCollisions == null) return false;
        final SubLevelEntityCollision.FirstCollisionInfo first = collision.firstCollisions.get(subLevel);
        if (first == null) return false;

        final Pose3dc pose = subLevel.logicalPose();
        final Vector3d targetUp = pose.transformNormalInverse(new Vector3d(first.globalDirection()), new Vector3d());
        if (targetUp.lengthSquared() < 1.0e-9) return false;
        AdhesionMath.snapAxis(targetUp.normalize(), targetUp);

        final Vector3dc currentUp = state.currentLocalUp();
        if (Math.abs(currentUp.dot(targetUp)) > AdhesionSettings.PERPENDICULAR_TOLERANCE) return false;

        final double eyeHeight = player.getEyeHeight();
        final double halfWidth = player.getBbWidth() * 0.5;
        final Vector3d pivotLocal = SurfaceProbe.localPivot(player, pose, new Vector3d());

        final SurfaceProbe.Hit fromHit = SurfaceProbe.probe(player, subLevel, pivotLocal, currentUp,
                eyeHeight + AdhesionSettings.NEAR_SUPPORT_GAP);
        final SurfaceProbe.Hit toHit = SurfaceProbe.probe(player, subLevel, pivotLocal, targetUp,
                halfWidth + AdhesionSettings.CONTACT_REACH);
        if (fromHit == null || toHit == null) return false;

        if (!cornerIsClear(player, subLevel, pivotLocal, currentUp, targetUp,
                fromHit.planeCoordinate(), toHit.planeCoordinate(), eyeHeight, halfWidth)) {
            return false;
        }

        return state.beginPlaneChange(targetUp, fromHit.planeCoordinate(), toHit.planeCoordinate(),
                eyeHeight, halfWidth);
    }

    /** Walks the whole arc up front and refuses it unless every step and the destination are free. */
    public static boolean cornerIsClear(final Player player, final SubLevel subLevel, final Vector3dc pivotLocal,
                                        final Vector3dc fromUp, final Vector3dc toUp, final double fromPlane,
                                        final double toPlane, final double eyeHeight, final double halfWidth) {
        final PlaneTransition arc = PlaneTransition.corner(fromUp, toUp, fromPlane, toPlane, eyeHeight, halfWidth,
                AdhesionSettings.CORNER_TICKS);
        if (arc == null) return false;

        final Vector3d previous = new Vector3d();
        final Vector3d sample = new Vector3d();
        for (int i = 0; i <= AdhesionSettings.ARC_SAMPLES; i++) {
            arc.pivotAt(i / (double) AdhesionSettings.ARC_SAMPLES, pivotLocal, sample);
            if (i > 0 && !SurfaceProbe.isClear(player, subLevel, previous, sample)) return false;
            previous.set(sample);
        }

        final double headRoom = player.getBbHeight() - eyeHeight + 0.05;
        if (!SurfaceProbe.isClear(player, subLevel, sample, toUp, headRoom)) return false;

        final Vector3d side = new Vector3d(fromUp).cross(toUp).normalize();
        final double reach = halfWidth + 0.02;
        return SurfaceProbe.isClear(player, subLevel, sample, side, reach)
                && SurfaceProbe.isClear(player, subLevel, sample, side, -reach);
    }

    /**
     * Places the pivot on the corner arc.
     *
     * <p>The position is absolute, recomputed from the planes every tick rather than integrated,
     * so it cannot drift and any error is corrected on the next tick. Sable rotates the hitbox
     * around the vanilla eye position, so that is the point being placed here; treating the feet
     * as the pivot is what left them behind the wall.
     */
    public static void applyCornerPosition(final Player player, final AdhesionState state, final SubLevel subLevel) {
        final Pose3dc pose = subLevel.logicalPose();
        final Vector3d pivotLocal = SurfaceProbe.localPivot(player, pose, new Vector3d());
        final Vector3d targetLocal = state.transitionPivotTarget(pivotLocal, new Vector3d());
        if (targetLocal == null) return;
        final Vector3d targetWorld = pose.transformPosition(targetLocal, new Vector3d());
        player.setPos(targetWorld.x, targetWorld.y - player.getEyeHeight(), targetWorld.z);
    }

    /** Replaces the world-down pull with a pull into the current surface. */
    public static void applyLocalGravity(final Player player, final AdhesionState state) {
        if (player.isNoGravity()) return;
        final Quaterniondc orientation = state.orientationAt(1.0f);
        if (orientation == null) return;

        final Vector3d up = orientation.transform(new Vector3d(AdhesionMath.UP), new Vector3d());
        if (up.lengthSquared() < 1.0e-9) return;
        up.normalize();

        final double gravity = player.getAttributeValue(Attributes.GRAVITY);
        final Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(
                motion.x - up.x * AdhesionSettings.ADHESION_PULL,
                motion.y + gravity - up.y * AdhesionSettings.ADHESION_PULL,
                motion.z - up.z * AdhesionSettings.ADHESION_PULL);
    }

    /** Keeps adhesion alive while there is a surface under the pivot, including mid-jump. */
    public static void updateSupport(final Player player, final AdhesionState state, final SubLevel subLevel,
                                     final @Nullable SubLevelEntityCollision.CollisionInfo collision) {
        final double support = SurfaceProbe.distanceToSurface(player, subLevel, state.currentLocalUp(),
                AdhesionSettings.JUMP_SUPPORT_GAP);
        state.setSupportDistance(support);

        final boolean standing = (collision != null && collision.trackingSubLevel == subLevel
                && collision.verticalCollisionBelow)
                || (!Double.isNaN(support)
                    && support <= player.getEyeHeight() + AdhesionSettings.NEAR_SUPPORT_GAP);

        if (standing) {
            state.resetSupportMisses();
            player.resetFallDistance();
        } else if (!Double.isNaN(support)) {
            state.resetSupportMisses();
        } else if (state.missSupport() > AdhesionSettings.SUPPORT_GRACE_TICKS) {
            state.detach();
        }
    }

    public static void clearInheritedVelocity(final Player player) {
        if (player instanceof final LivingEntityMovementExtension extension) {
            extension.sable$getInheritedVelocity().zero();
        }
    }

    public static boolean ownsMovement(final Player player) {
        return !player.level().isClientSide() || player.isLocalPlayer();
    }

    public static boolean eligible(final Player player) {
        return !player.isSpectator()
                && !player.isPassenger()
                && !player.getAbilities().flying
                && !player.isFallFlying()
                && !player.isSleeping()
                && !player.isInWater()
                && !player.isInLava();
    }

    public static AdhesionState createState(final Player player) {
        return ((AdhesionAccess) player).centrifugalForce$getOrCreateAdhesionState();
    }

    public static @Nullable AdhesionState peekState(final Player player) {
        return ((AdhesionAccess) player).centrifugalForce$peekAdhesionState();
    }
}
