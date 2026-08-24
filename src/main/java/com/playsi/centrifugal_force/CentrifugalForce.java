package com.playsi.centrifugal_force;

import com.mojang.logging.LogUtils;
import com.playsi.centrifugal_force.internal.AdhesionEngine;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(CentrifugalForce.MOD_ID)
public final class CentrifugalForce {
    public static final String MOD_ID = "centrifugal_force";
    public static final String MOD_NAME = "Centrifugal Force";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CentrifugalForce(final IEventBus modBus) {
        NeoForge.EVENT_BUS.register(AdhesionEngine.INSTANCE);
        LOGGER.info("{} loaded: Sable-native oriented adhesion enabled", MOD_NAME);
    }
}
