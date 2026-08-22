package dev.gamir.sable_cf.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
import dev.gamir.sable_cf.physics.ForceState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * {@code /sable_cf}.
 *
 * <h2>One knob per subsystem</h2>
 *
 * <p>Every force takes its strength directly - {@code /sable_cf air_resistance 1.4} - with no
 * sub-parameter to discover and no second number to combine with it mentally. The shaping constants
 * that used to be exposed as {@code reference_speed} still exist, but they are fixed in
 * {@link CfConfig} where they can be read once rather than tuned forever. A knob you have to
 * combine with another knob in your head is not a knob you can tune.</p>
 *
 * <p>Passing a value also enables the subsystem, because typing a strength and getting no effect is
 * never what anyone meant by it.</p>
 *
 * <h2>Colour convention, applied everywhere</h2>
 *
 * <p>Labels are grey and values are coloured, always the same way, so the display can be read at a
 * glance while you are being flung around rather than parsed:</p>
 *
 * <ul>
 *   <li><b>green</b> on, holding, fine - <b>dark grey</b> off or absent</li>
 *   <li><b>white</b> a normal reading, <b>yellow</b> getting significant, <b>red</b> at or past the
 *       point where you lose your footing</li>
 *   <li><b>aqua</b> a number you set, so a configured value is never confused with a measured one</li>
 * </ul>
 *
 * <p>No column padding. Values sit one space after their label; runs of spaces used as alignment
 * are what made the old output hard to read in a chat window that is already narrow.</p>
 */
public final class CfCommands {

    private static final ChatFormatting LABEL = ChatFormatting.GRAY;
    private static final ChatFormatting SET = ChatFormatting.AQUA;
    private static final ChatFormatting OFF = ChatFormatting.DARK_GRAY;
    private static final ChatFormatting OK = ChatFormatting.GREEN;
    private static final ChatFormatting WARN = ChatFormatting.YELLOW;
    private static final ChatFormatting DANGER = ChatFormatting.RED;
    private static final ChatFormatting UNIT = ChatFormatting.DARK_GRAY;

    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("sable_cf")
                // Bare /sable_cf prints the status: the thing you want most often should be the
                // least to type.
                .executes(CfCommands::status)
                .then(Commands.literal("status").executes(CfCommands::status))
                .then(Commands.literal("debug_overlay")
                        .executes(context -> toggleOverlay(context, null))
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(context -> toggleOverlay(
                                        context, BoolArgumentType.getBool(context, "value")))))
                .then(knob("centrifugal_force", CfConfig.CENTRIFUGAL_ENABLED,
                        CfConfig.CENTRIFUGAL_STRENGTH, 0.0, 4.0))
                .then(knob("air_resistance", CfConfig.AIR_ENABLED,
                        CfConfig.AIR_STRENGTH, 0.0, 4.0))
                .then(knob("grip", CfConfig.GRIP_ENABLED,
                        CfConfig.GRIP_STRENGTH, 0.0, 4.0))
                .then(knob("camera", CfConfig.CAMERA_ENABLED,
                        CfConfig.CAMERA_AMOUNT, 0.0, 2.0))
                .then(knob("hitbox", CfConfig.HITBOX_ENABLED,
                        CfConfig.HITBOX_AMOUNT, 0.0, 1.0))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(2))
                        .executes(CfCommands::reset));
    }

    /** {@code <name> enable|disable|<value>}, plus bare {@code <name>} to read it back. */
    private static LiteralArgumentBuilder<CommandSourceStack> knob(
            final String name,
            final ModConfigSpec.BooleanValue enabled,
            final ModConfigSpec.DoubleValue strength,
            final double min,
            final double max) {

        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    reply(context, describe(name, enabled, strength));
                    return 1;
                })
                .then(Commands.literal("enable").executes(context -> {
                    enabled.set(true);
                    enabled.save();
                    reply(context, describe(name, enabled, strength));
                    return 1;
                }))
                .then(Commands.literal("disable").executes(context -> {
                    enabled.set(false);
                    enabled.save();
                    reply(context, describe(name, enabled, strength));
                    return 1;
                }))
                .then(Commands.argument("strength", DoubleArgumentType.doubleArg(min, max))
                        .executes(context -> {
                            strength.set(DoubleArgumentType.getDouble(context, "strength"));
                            strength.save();

                            // Setting a strength implies wanting it on.
                            if (!enabled.get()) {
                                enabled.set(true);
                                enabled.save();
                            }

                            reply(context, describe(name, enabled, strength));
                            return 1;
                        }));
    }

    private static int toggleOverlay(
            final CommandContext<CommandSourceStack> context, final Boolean explicit) {

        final boolean value = explicit != null ? explicit : !CfConfig.DEBUG_OVERLAY.get();

        CfConfig.DEBUG_OVERLAY.set(value);
        CfConfig.DEBUG_OVERLAY.save();

        reply(context, label("debug overlay").append(onOff(value)));

        return 1;
    }

    private static int reset(final CommandContext<CommandSourceStack> context) {
        set(CfConfig.CENTRIFUGAL_ENABLED, true);
        set(CfConfig.CENTRIFUGAL_STRENGTH, 1.0);
        set(CfConfig.CORIOLIS_STRENGTH, 0.35);
        set(CfConfig.AIR_ENABLED, true);
        set(CfConfig.AIR_STRENGTH, 1.0);
        set(CfConfig.GRIP_ENABLED, true);
        set(CfConfig.GRIP_STRENGTH, 0.85);
        set(CfConfig.HITBOX_ENABLED, true);
        set(CfConfig.HITBOX_AMOUNT, 1.0);
        set(CfConfig.CAMERA_ENABLED, true);
        set(CfConfig.CAMERA_AMOUNT, 1.0);
        set(CfConfig.CAMERA_RESPONSE, 9.0);
        set(CfConfig.CAMERA_DAMPING, 1.0);
        set(CfConfig.CAMERA_JOLT_GAIN, 1.6);
        set(CfConfig.CAMERA_LOOP_SUPPRESSION, 0.85);
        set(CfConfig.CAMERA_WALK_DAMPING, 0.65);

        reply(context, Component.literal("Reset to defaults.").withStyle(OK));

        return 1;
    }

    private static <T> void set(final ModConfigSpec.ConfigValue<T> value, final T fresh) {
        value.set(fresh);
        value.save();
    }

    // ---------------------------------------------------------------- status

    private static int status(final CommandContext<CommandSourceStack> context) {
        final ForceState state = CentrifugalHandler.STATE;

        final MutableComponent header = Component.literal("Sable CF ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(state.active
                        ? Component.literal("on a sub-level").withStyle(OK)
                        : Component.literal("not on a sub-level").withStyle(OFF));

        reply(context, header);

        reply(context, join(
                describe("centrifugal", CfConfig.CENTRIFUGAL_ENABLED, CfConfig.CENTRIFUGAL_STRENGTH),
                describe("air", CfConfig.AIR_ENABLED, CfConfig.AIR_STRENGTH),
                describe("grip", CfConfig.GRIP_ENABLED, CfConfig.GRIP_STRENGTH)));

        reply(context, join(
                describe("camera", CfConfig.CAMERA_ENABLED, CfConfig.CAMERA_AMOUNT),
                describe("hitbox", CfConfig.HITBOX_ENABLED, CfConfig.HITBOX_AMOUNT),
                label("overlay").append(onOff(CfConfig.DEBUG_OVERLAY.get()))));

        if (!state.active) {
            reply(context, Component.literal("Stand on a Sable contraption for live readings.")
                    .withStyle(OFF));
            return 1;
        }

        // Press in g, because "1.4 g" is a quantity people have intuition about and "44.8 m/s^2"
        // is not.
        final double pressG = state.press / CfConfig.GRAVITY;

        reply(context, join(
                label("press").append(number(pressG, pressColour(pressG))).append(unit(" g")),
                label("load").append(number(state.tangentialLoad,
                        state.tangentialLoad <= state.hold ? OK : DANGER)),
                label("hold").append(number(state.hold, ChatFormatting.WHITE))));

        final MutableComponent footing = state.gripped
                ? (state.slipping
                        ? Component.literal("sliding").withStyle(WARN)
                        : Component.literal("holding").withStyle(OK))
                : Component.literal("no footing").withStyle(DANGER);

        final MutableComponent third = state.wallRide
                ? Component.literal(" wall ride").withStyle(ChatFormatting.LIGHT_PURPLE)
                : Component.empty();

        reply(context, join(
                label("footing").append(footing).append(third),
                label("brace").append(onOff(state.bracing)),
                label("tilt").append(number(state.tilt, state.tilt > 0.5 ? WARN : ChatFormatting.WHITE))));

        final double spin = state.omega.length();
        final double jolt = state.angularAcceleration.length();

        reply(context, join(
                label("spin").append(number(spin, spin > 2.5 ? WARN : ChatFormatting.WHITE))
                        .append(unit(" rad/s")),
                label("jolt").append(number(jolt, jolt > 6.0 ? WARN : ChatFormatting.WHITE))
                        .append(unit(" rad/s2")),
                label("air").append(number(state.airVelocity.length(), ChatFormatting.WHITE))
                        .append(unit(" m/s"))));

        return 1;
    }

    private static ChatFormatting pressColour(final double pressG) {
        if (pressG < CfConfig.GRIP_MIN_PRESS_G.get()) {
            return DANGER;
        }

        if (pressG < 0.75) {
            return WARN;
        }

        if (pressG > 3.0) {
            return DANGER;
        }

        if (pressG > 1.5) {
            return WARN;
        }

        return ChatFormatting.WHITE;
    }

    // ---------------------------------------------------------------- component helpers

    private static MutableComponent describe(
            final String name,
            final ModConfigSpec.BooleanValue enabled,
            final ModConfigSpec.DoubleValue strength) {

        final MutableComponent value = enabled.get()
                ? number(strength.get(), SET)
                : Component.literal("off").withStyle(OFF);

        return label(name).append(value);
    }

    /** Grey label plus exactly one space. No padding, ever. */
    private static MutableComponent label(final String text) {
        return Component.literal(text + " ").withStyle(LABEL);
    }

    private static MutableComponent number(final double value, final ChatFormatting colour) {
        return Component.literal(String.format(Locale.ROOT, "%.2f", value)).withStyle(colour);
    }

    private static MutableComponent unit(final String text) {
        return Component.literal(text).withStyle(UNIT);
    }

    private static MutableComponent onOff(final boolean value) {
        return value
                ? Component.literal("on").withStyle(OK)
                : Component.literal("off").withStyle(OFF);
    }

    /** Joins fields with a single separator, so nothing is aligned by padding. */
    private static MutableComponent join(final MutableComponent... parts) {
        final MutableComponent out = Component.empty();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(Component.literal(" \u00b7 ").withStyle(OFF));
            }

            out.append(parts[i]);
        }

        return out;
    }

    private static void reply(
            final CommandContext<CommandSourceStack> context, final Component message) {
        context.getSource().sendSuccess(() -> message, false);
    }

    private CfCommands() {
    }
}
