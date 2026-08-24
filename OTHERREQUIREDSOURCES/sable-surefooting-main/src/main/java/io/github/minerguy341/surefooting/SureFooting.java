package io.github.minerguy341.surefooting;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SureFooting.MOD_ID)
public final class SureFooting {

    public static final String MOD_ID = "surefooting";
    public static final Logger LOGGER = LoggerFactory.getLogger("Sable: Sure Footing");

    public SureFooting(final ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SureFootingConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, SureFootingServerConfig.SPEC);

        // Non-player entities are server-authoritative; this handler runs on the logical server
        // (dedicated or integrated) and is what keeps items/mobs on fast contraptions.
        NeoForge.EVENT_BUS.register(new EntityCarryHandler());

        // The player and particle fixes are client-side: players are client-authoritative for
        // movement (the server adopts the client's tracking state from movement packets), and
        // particles only exist on the client.
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(new JumpCarryHandler());
            NeoForge.EVENT_BUS.register(new ParticleAnchorHandler());

            // Enables the "Config" button on the Mods screen, using NeoForge's built-in
            // auto-generated configuration UI for our ModConfigSpec.
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
    }
}
