package com.playsi.centrifugal_force.mixin;

import com.playsi.centrifugal_force.internal.AdhesionAccess;
import com.playsi.centrifugal_force.internal.AdhesionState;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable measures inherited motion from the feet of the rotated box but starts that measurement from
 * the unrotated position, which leaves a constant push towards the raised side of a tilted plane.
 * The carry is plain sub-level motion, so it is written here, after collision and before
 * LivingEntity#travel consumes it.
 */
@Mixin(Entity.class)
public abstract class EntityFrameCarryMixin {
    @Inject(method = "move", at = @At("TAIL"))
    private void centrifugalForce$frameCarry(final MoverType type, final Vec3 movement, final CallbackInfo callback) {
        final AdhesionState state = ((AdhesionAccess) this).centrifugalForce$peekAdhesionState();
        if (state == null || !state.isActive()) return;

        final SubLevelEntityCollision.CollisionInfo info = ((EntityMovementExtension) this).sable$getCollisionInfo();
        if (info != null) info.inheritedMotion = state.frameCarry();
    }
}
