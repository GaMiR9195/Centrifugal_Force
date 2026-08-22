package dev.gamir.sable_cf;

import dev.gamir.sable_cf.client.ClientSetup;
import dev.gamir.sable_cf.command.CfCommands;
import dev.gamir.sable_cf.physics.BodyFrameTicker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point.
 *
 * <p>Three registrations, and the split between them is the whole architecture:</p>
 *
 * <ul>
 *   <li>The config is <b>COMMON</b>, not client. It gates the rotated hitbox, and a hitbox only one
 *       side believes in is worse than no hitbox change at all - the server keeps testing an
 *       upright box, finds the player inside blocks, and shoves them out. Common config, common
 *       code, same answer on both sides.</li>
 *   <li>{@link BodyFrameTicker} runs on <b>both</b> sides. It recomputes body orientation from
 *       Sable's pose, which both sides already have, so the two agree without a single packet.</li>
 *   <li>Only the camera and the debug arrows are client-side, because only they are actually about
 *       what you see.</li>
 * </ul>
 */
@Mod(SableCf.MOD_ID)
public final class SableCf {

    public static final String MOD_ID = "sable_cf";

    public static final Logger LOGGER = LoggerFactory.getLogger("sable_cf");

    public SableCf(final IEventBus modBus, final ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CfConfig.SPEC);

        NeoForge.EVENT_BUS.register(new BodyFrameTicker());
        NeoForge.EVENT_BUS.addListener(CfCommands::onRegisterCommands);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }
}
