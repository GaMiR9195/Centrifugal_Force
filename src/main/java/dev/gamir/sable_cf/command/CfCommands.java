package dev.gamir.sable_cf.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.gamir.sable_cf.CfConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code /sable_cf}. Client commands, so they need no permission level and work on any server.
 *
 * <p>Every one of them writes into the config and saves, so the value survives a restart and there
 * is no second copy of the defaults anywhere. Tuning this kind of feel is a loop of
 * change-something-while-spinning, and that loop should not include a restart.</p>
 *
 * <p>The knobs exposed here are the ones you actually reach for mid-session. The rest - Euler and
 * Coriolis scaling, brace bonus, the safety clamp, arrow scales - live in the config file and the
 * Mods screen, because needing them means you are designing rather than playing.</p>
 *
 * <p>(Spelling note, since it comes up: it is <i>centrifugal</i> - from Latin <i>centrum</i> +
 * <i>fugere</i>, "to flee the centre". Its partner is centri<i>petal</i>, "seeking the centre".)</p>
 */
public final class CfCommands {

    private static final String VALUE = "value";

    @SubscribeEvent
    public void onRegisterClientCommands(final RegisterClientCommandsEvent event) {
        event.getDispatcher().register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("sable_cf")
                .executes(CfCommands::status)

                .then(Commands.literal("status")
                        .executes(CfCommands::status))

                .then(Commands.literal("debug_overlay")
                        .executes(context -> setFlag(context, CfConfig.DEBUG_OVERLAY,
                                !CfConfig.DEBUG_OVERLAY.get(), "debug overlay"))
                        .then(Commands.argument(VALUE, BoolArgumentType.bool())
                                .executes(context -> setFlag(context, CfConfig.DEBUG_OVERLAY,
                                        BoolArgumentType.getBool(context, VALUE), "debug overlay"))))

                .then(Commands.literal("centrifugal_force")
                        .then(Commands.literal("enable")
                                .executes(context -> setFlag(context, CfConfig.CENTRIFUGAL_ENABLED,
                                        true, "centrifugal force")))
                        .then(Commands.literal("disable")
                                .executes(context -> setFlag(context, CfConfig.CENTRIFUGAL_ENABLED,
                                        false, "centrifugal force")))
                        .then(Commands.literal("strength")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(0.0, 4.0))
                                        .executes(context -> setValue(context, CfConfig.CENTRIFUGAL_STRENGTH,
                                                "centrifugal strength", "x physical")))))

                .then(Commands.literal("air_resistance")
                        .then(Commands.literal("enable")
                                .executes(context -> setFlag(context, CfConfig.AIR_ENABLED,
                                        true, "air resistance")))
                        .then(Commands.literal("disable")
                                .executes(context -> setFlag(context, CfConfig.AIR_ENABLED,
                                        false, "air resistance")))
                        .then(Commands.literal("reference_speed")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(2.0, 200.0))
                                        .executes(context -> setValue(context, CfConfig.AIR_REFERENCE_SPEED,
                                                "air reference speed", "m/s = 1 g of drag")))))

                .then(Commands.literal("grip")
                        .then(Commands.literal("friction")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(0.0, 4.0))
                                        .executes(context -> setValue(context, CfConfig.GRIP_FRICTION,
                                                "grip friction", "mu")))))

                .then(Commands.literal("camera")
                        .then(Commands.literal("enable")
                                .executes(context -> setFlag(context, CfConfig.CAMERA_ENABLED,
                                        true, "camera tilt")))
                        .then(Commands.literal("disable")
                                .executes(context -> setFlag(context, CfConfig.CAMERA_ENABLED,
                                        false, "camera tilt")))
                        .then(Commands.literal("response")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(1.0, 40.0))
                                        .executes(context -> setValue(context, CfConfig.CAMERA_RESPONSE,
                                                "camera response", "rad/s"))))
                        .then(Commands.literal("pitch_response")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(0.0, 1.0))
                                        .executes(context -> setValue(context, CfConfig.CAMERA_PITCH_RESPONSE,
                                                "camera pitch response", "of full"))))
                        .then(Commands.literal("deck_lean")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(0.0, 1.0))
                                        .executes(context -> setValue(context, CfConfig.CAMERA_DECK_LEAN,
                                                "camera deck lean", "of full"))))
                        .then(Commands.literal("max_tilt")
                                .then(Commands.argument(VALUE, DoubleArgumentType.doubleArg(0.0, 90.0))
                                        .executes(context -> setValue(context, CfConfig.CAMERA_MAX_TILT_DEG,
                                                "camera max tilt", "deg")))));
    }

    private static int setFlag(final CommandContext<CommandSourceStack> context,
                               final ModConfigSpec.BooleanValue config,
                               final boolean value,
                               final String label) {
        config.set(value);
        config.save();
        feedback(context, label + ": " + (value ? "on" : "off"));
        return 1;
    }

    private static int setValue(final CommandContext<CommandSourceStack> context,
                                final ModConfigSpec.DoubleValue config,
                                final String label,
                                final String unit) {
        final double value = DoubleArgumentType.getDouble(context, VALUE);
        config.set(value);
        config.save();
        feedback(context, String.format("%s = %.3f %s", label, value, unit));
        return 1;
    }

    private static int status(final CommandContext<CommandSourceStack> context) {
        feedback(context, "--- sable_cf ---");
        feedback(context, String.format("centrifugal_force  %s   strength %.2f",
                onOff(CfConfig.CENTRIFUGAL_ENABLED.get()), CfConfig.CENTRIFUGAL_STRENGTH.get()));
        feedback(context, String.format("air_resistance     %s   reference_speed %.1f m/s",
                onOff(CfConfig.AIR_ENABLED.get()), CfConfig.AIR_REFERENCE_SPEED.get()));
        feedback(context, String.format("grip               friction %.2f   brace x%.2f",
                CfConfig.GRIP_FRICTION.get(), CfConfig.GRIP_BRACE_BONUS.get()));
        feedback(context, String.format("camera             %s   response %.1f   pitch %.2f   lean %.2f   max %.0f deg",
                onOff(CfConfig.CAMERA_ENABLED.get()), CfConfig.CAMERA_RESPONSE.get(),
                CfConfig.CAMERA_PITCH_RESPONSE.get(), CfConfig.CAMERA_DECK_LEAN.get(),
                CfConfig.CAMERA_MAX_TILT_DEG.get()));
        feedback(context, "debug_overlay      " + onOff(CfConfig.DEBUG_OVERLAY.get()));

        if (!ModList.get().isLoaded("aero_cam_sync")) {
            feedback(context, "note: Aeronautics Camera Sync is not installed, so camera tilt cannot run.");
        }

        return 1;
    }

    private static String onOff(final boolean value) {
        return value ? "on " : "off";
    }

    private static void feedback(final CommandContext<CommandSourceStack> context, final String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}
