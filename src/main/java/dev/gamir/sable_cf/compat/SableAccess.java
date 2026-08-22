package dev.gamir.sable_cf.compat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Everything this mod needs from Sable, in one place.
 *
 * <p>Sable's movement state lives behind {@code @ApiStatus.Internal} duck interfaces, and the
 * velocity helper is only reachable through a static. Funnelling all of it through here means the
 * physics code reads as physics, and the day Sable renames something exactly one file breaks. Every
 * method is null-safe and returns a neutral value off a sub-level, because "is this player on a
 * contraption" is a question the callers ask constantly and should not have to guard.</p>
 */
public final class SableAccess {

    /** The sub-level this entity is currently standing on, or null. */
    @Nullable
    public static SubLevel tracking(final Entity entity) {
        if (entity instanceof EntityMovementExtension extension) {
            return extension.sable$getTrackingSubLevel();
        }

        return null;
    }

    public static void setTracking(final Entity entity, @Nullable final SubLevel subLevel) {
        if (entity instanceof EntityMovementExtension extension) {
            extension.sable$setTrackingSubLevel(subLevel);
        }
    }

    /**
     * Velocity the entity has inherited from the sub-level, m/s. This is what Sure Footing
     * preserves across a jump, so it is also what has to be excluded from "the player's own
     * movement".
     */
    public static Vec3 inheritedVelocity(final Entity entity) {
        if (entity instanceof LivingEntity && entity instanceof LivingEntityMovementExtension extension) {
            final Vector3d inherited = new Vector3d(extension.sable$getInheritedVelocity());

            if (inherited.isFinite()) {
                return new Vec3(inherited.x, inherited.y, inherited.z);
            }
        }

        return Vec3.ZERO;
    }

    /**
     * World velocity, m/s, of the sub-level material at a <b>world</b> position.
     *
     * <p>Exact: Sable computes it from the pose delta, so it already includes rotation about the
     * true rotation point, the sub-level's scale, and any linear drift. Deriving the same thing by
     * hand from omega and a guessed centre is how you end up with forces that are right on a
     * turntable and wrong on anything that also moves.</p>
     */
    public static Vec3 pointVelocity(final SubLevel subLevel, final Vec3 worldPosition) {
        final Level level = subLevel.getLevel();

        if (level == null) {
            return Vec3.ZERO;
        }

        final Vec3 local = subLevel.logicalPose().transformPositionInverse(worldPosition);

        return sanitise(Sable.HELPER.getVelocity(level, subLevel, local));
    }

    /**
     * World velocity, m/s, of the sub-level material at a <b>local</b> position.
     *
     * <p>The local overload is the one that matters for acceleration. A local point is a fixed
     * piece of the contraption, so sampling the same local point on two ticks and differencing
     * gives the material acceleration of the deck. Doing the same with a world point would mix in
     * the deck sliding past that point in space and give a meaningless number.</p>
     */
    public static Vec3 localPointVelocity(final SubLevel subLevel, final Vector3dc localPosition) {
        final Level level = subLevel.getLevel();

        if (level == null || !localPosition.isFinite()) {
            return Vec3.ZERO;
        }

        final Vec3 local = new Vec3(localPosition.x(), localPosition.y(), localPosition.z());

        return sanitise(Sable.HELPER.getVelocity(level, subLevel, local));
    }

    /**
     * Wind at a world position, m/s.
     *
     * <p>Sable exposes absolute velocity and velocity-relative-to-air but not the air itself, so
     * the difference of the two is the wind. Needed because drag has to act on the player's speed
     * through the air, not through the world - inside a moving airship's hull the air moves with
     * the ship and there should be no drag at all.</p>
     */
    public static Vec3 wind(final Level level, final Vec3 position) {
        final Vec3 absolute = Sable.HELPER.getVelocity(level, position);
        final Vec3 relative = Sable.HELPER.getVelocityRelativeToAir(level, position);

        return sanitise(absolute.subtract(relative));
    }

    private static Vec3 sanitise(@Nullable final Vec3 value) {
        if (value == null
                || Double.isNaN(value.x) || Double.isNaN(value.y) || Double.isNaN(value.z)
                || Double.isInfinite(value.x) || Double.isInfinite(value.y) || Double.isInfinite(value.z)) {
            return Vec3.ZERO;
        }

        return value;
    }

    private SableAccess() {
    }
}
