package dev.gamir.sable_cf.mixin;

import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one mixin into vanilla, and the only reason this mod needs a mixin config at all.
 *
 * <p>It does exactly two things: it gives every entity somewhere to keep a {@link BodyFrame}, and
 * it lets that body frame refit the collision box. Both are things no API can provide from outside,
 * because Minecraft has no hook for "what shape am I" and no way to attach a field to an entity.</p>
 *
 * <p>{@code makeBoundingBox} is the right target rather than {@code setBoundingBox} or
 * {@code getBoundingBox}: it is the single place vanilla derives the box from the position and the
 * dimensions, it is called on both sides, and returning a different box from it leaves every
 * downstream consumer - collision, suffocation, entity lookups, rendering the hitbox - reading the
 * same answer. Injecting at RETURN rather than overwriting keeps other mods' injectors working.</p>
 *
 * <p>Players only. Rotating mobs would need their AI and pathfinding to understand a rotated body,
 * which they emphatically do not, and rotating item entities would be pure cost.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements BodyFrameHolder {

    @Unique
    @Nullable
    private BodyFrame sable_cf$frame;

    @Override
    public BodyFrame sable_cf$bodyFrame() {
        if (this.sable_cf$frame == null) {
            this.sable_cf$frame = new BodyFrame();
        }

        return this.sable_cf$frame;
    }

    @Override
    @Nullable
    public BodyFrame sable_cf$bodyFrameOrNull() {
        return this.sable_cf$frame;
    }

    @Inject(method = "makeBoundingBox", at = @At("RETURN"), cancellable = true)
    private void sable_cf$refitBoundingBox(final CallbackInfoReturnable<AABB> callback) {
        final BodyFrame frame = this.sable_cf$frame;

        // Null on every entity that has never been on a sub-level, and untilted on every player
        // standing on a normal floor. This path runs several times per entity per tick, so the
        // common case has to cost one field read and one boolean.
        if (frame == null || !frame.isTilted()) {
            return;
        }

        final Entity self = (Entity) (Object) this;

        if (!(self instanceof Player)) {
            return;
        }

        final AABB refitted = frame.fitBoundingBox(self, callback.getReturnValue());

        if (refitted != null) {
            callback.setReturnValue(refitted);
        }
    }
}
