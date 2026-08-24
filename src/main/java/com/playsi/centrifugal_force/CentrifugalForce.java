package com.playsi.centrifugal_force;

import com.playsi.centrifugal_force.internal.AdhesionEngine;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(CentrifugalForce.MOD_ID)
public final class CentrifugalForce {
    public static final String MOD_ID = "centrifugal_force";

    public CentrifugalForce(final IEventBus modBus) {
        NeoForge.EVENT_BUS.register(AdhesionEngine.INSTANCE);
    }
}
