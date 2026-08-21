package dev.gamir.sable_cf.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Every single line of Sable this mod touches. One file on purpose.
 *
 * <p>Two of these calls go through {@code dev.ryanhcode.sable.mixinterface}, which is
 * {@code @ApiStatus.Internal} - Sable makes no promise about it across versions. That is exactly
 * why the runtime dependency is pinned to {@code [2.0.0,2.1)} rather than open-ended, and exactly
 * why they are all in here: when Sable moves, this is the only file to read. Sure Footing pins
 * itself for the same reason and against the same interfaces.</p>
 *
 * <p>Everything else is {@code Sable.HELPER}, which is public and does the interesting work.</p>
 */
public final class SableAccess {

    /** The sub-level the entity is standing on or locked to, or null. */
    public static SubLevel tracking(final Entity entity) {
        return Sable.HELPER.getTrackingSubLevel(entity);
    }

    /**
     * Writes the tracking sub-level. Internal duck interface, and the only write this mod makes
     * into Sable's own state - see {@code CfConfig.RELEASE_TRACKING} for why it is off by default.
     */
    public static void setTracking(final Entity entity, final SubLevel subLevel) {
        ((EntityMovementExtension) entity).sable$setTrackingSubLevel(subLevel);
    }

    /**
     * The velocity Sable hands an entity when it stops carrying it, m/s. Read-only, and only used
     * for the debug readout - it is the number that answers "did Sable already give me the deck's
     * momentum, or do I need to?", and the answer is that it did.
     */
    public static Vector3d inheritedVelocity(final LivingEntity entity) {
        return ((LivingEntityMovementExtension) entity).sable$getInheritedVelocity();
    }

    /**
     * World-space velocity of the point of the sub-level currently under a world position, in m/s.
     *
     * <p>Sable wants the position in sub-level local space and returns the global velocity, so the
     * inverse transform is part of the call. On the client this resolves to a pose difference times
     * 20; on the server it comes off the rigid body. Either way it already includes both the spin
     * and the linear drift of the contraption, which is why this mod never has to guess at either.</p>
     */
    public static Vec3 pointVelocity(final SubLevel subLevel, final Vec3 worldPos) {
        final Vec3 local = subLevel.logicalPose().transformPositionInverse(worldPos);
        return Sable.HELPER.getVelocity(subLevel.getLevel(), subLevel, local);
    }

    /**
     * Wind at a world position, m/s, including whatever Aeronautics and friends registered as wind
     * providers.
     *
     * <p>There is no {@code getWind} in Sable, but there does not need to be:
     * {@code getVelocityRelativeToAir} is defined as {@code getVelocity - wind}, so subtracting the
     * two recovers the wind itself. It works outside a sub-level plot as well, where
     * {@code getVelocity} is zero and the relative value is just {@code -wind} - which is the case
     * that matters, since a player on a contraption in the overworld is at overworld coordinates.</p>
     */
    public static Vec3 wind(final Level level, final Vec3 worldPos) {
        return Sable.HELPER.getVelocity(level, worldPos)
                .subtract(Sable.HELPER.getVelocityRelativeToAir(level, worldPos));
    }

    private SableAccess() {
    }
}
