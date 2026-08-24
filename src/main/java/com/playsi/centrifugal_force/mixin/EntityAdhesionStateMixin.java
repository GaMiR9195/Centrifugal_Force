package com.playsi.centrifugal_force.mixin;

import com.playsi.centrifugal_force.internal.AdhesionAccess;
import com.playsi.centrifugal_force.internal.AdhesionState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
public abstract class EntityAdhesionStateMixin implements AdhesionAccess {
    @Unique
    private AdhesionState centrifugalForce$adhesionState;

    @Override
    public AdhesionState centrifugalForce$getOrCreateAdhesionState() {
        if (this.centrifugalForce$adhesionState == null) {
            this.centrifugalForce$adhesionState = new AdhesionState();
        }
        return this.centrifugalForce$adhesionState;
    }

    @Override
    public @Nullable AdhesionState centrifugalForce$peekAdhesionState() {
        return this.centrifugalForce$adhesionState;
    }
}
