package dev.gamir.sable_cf.client;

import dev.gamir.sable_cf.SableCf;
import dev.gamir.sable_cf.command.CfCommands;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client wiring. Called from the mod constructor, and only on a client dist. */
public final class ClientSetup {

    public static void init(final ModContainer container) {
        NeoForge.EVENT_BUS.register(new CentrifugalHandler());
        NeoForge.EVENT_BUS.register(new DebugArrows());
        NeoForge.EVENT_BUS.register(new DebugHud());
        NeoForge.EVENT_BUS.register(new CfCommands());

        // Gives the Mods screen a working Config button for free - the commands are for tuning
        // while you are actually spinning, the screen is for reading the descriptions.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // AcsBridge is a separate class touched only from inside this branch, so ACS's classes are
        // never resolved when ACS is absent. Same trick for CfTiltSource, which is only ever
        // referenced from AcsBridge.
        if (ModList.get().isLoaded("aero_cam_sync")) {
            AcsBridge.init();
        } else {
            SableCf.LOGGER.info(
                    "Aeronautics Camera Sync is not installed - camera tilt is off. "
                            + "The physics and the debug overlay do not need it.");
        }
    }

    private ClientSetup() {
    }
}
