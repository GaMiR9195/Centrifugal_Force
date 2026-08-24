package com.playsi.centrifugal_force.mixin;

import com.playsi.centrifugal_force.internal.AdhesionAccess;
import com.playsi.centrifugal_force.internal.AdhesionState;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniondc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The single point where Sable is told how the entity is oriented. */
@Mixin(value = EntitySubLevelUtil.class, remap = false, priority = 1500)
public abstract class EntitySubLevelUtilMixin {
    @Inject(method = "getCustomEntityOrientation", at = @At("HEAD"), cancellable = true)
    private static void centrifugalForce$getOrientation(final Entity entity, final float partialTicks,
            final CallbackInfoReturnable<Quaterniondc> callback) {
        final AdhesionState state = ((AdhesionAccess) entity).centrifugalForce$peekAdhesionState();
        if (state == null) return;

        final Quaterniondc orientation = state.orientation(partialTicks);
        if (orientation != null) callback.setReturnValue(orientation);
    }

    @Inject(method = "hasCustomEntityOrientation", at = @At("HEAD"), cancellable = true)
    private static void centrifugalForce$hasOrientation(final Entity entity,
            final CallbackInfoReturnable<Boolean> callback) {
        final AdhesionState state = ((AdhesionAccess) entity).centrifugalForce$peekAdhesionState();
        if (state != null && state.isActive()) callback.setReturnValue(true);
    }
}
