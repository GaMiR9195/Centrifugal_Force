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
 * <h2>One knob per subsystem, and each one does what its name says</h2>
 *
 * <p>Every force takes its strength directly - {@code /sable_cf air_resistance 1.4} - with no
 * sub-parameter to discover. The shaping constants live in {@link CfConfig} where they can be read
 * once rather than tuned forever.</p>
 *
 * <p>{@code slip} is gone, and its absence is the point. It was half of {@code air_resistance}
 * under a second name: the outward creep that walks you to the rim of a drum is the same idea as
 * the headwind that drags you off one, so having them on separate switches meant turning air
 * resistance off did not stop you sliding. That is now {@code air_resistance}'s own {@code slide}.
 * The other thing {@code slip} controlled - climbing a wall the ride pins you to - is the opposite
 * force, the one that keeps you on, and it is {@code /sable_cf wall}.</p>
 *
 * <h2>Colour convention, applied everywhere</h2>
 *
 * <ul>
 *   <li><b>green</b> on, holding, fine - <b>dark grey</b> off or absent</li>
 *   <li><b>white</b> a normal reading, <b>yellow</b> getting significant, <b>red</b> at or past the
 *       point where you lose your footing</li>
 *   <li><b>aqua</b> a number you set, so a configured value is never confused with a measured one</li>
 * </ul>
 *
 * <p>No column padding. Values sit one space after their label.</p>
 */
public final class CfCommands {

    private static final ChatFormatting LABEL = ChatFormatting.GRAY;
    private static final ChatFormatting SET = ChatFormatting.AQUA;
    private static final ChatFormatting OFF = ChatFormatting.DARK_GRAY;
    private static final ChatFormatting OK = ChatFormatting.GREEN;
    private static final ChatFormatting WARN = ChatFormatting.YELLOW;
    private static final ChatFormatting DANGER = ChatFormatting.RED;
    private static final ChatFormatting UNIT = ChatFormatting.DARK_GRAY;

    private static final String[] FACES = {"+Y", "-Y", "+X", "-X", "+Z", "-Z"};

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
                        CfConfig.GRIP_STRENGTH, 0.0, 8.0))
                .then(knob("wall", CfConfig.WALL_ENABLED,
                        CfConfig.WALL_STRENGTH, 0.0, 2.0))
                .then(knob("camera", CfConfig.CAMERA_ENABLED,
                        CfConfig.CAMERA_AMOUNT, 0.0, 2.0))
                .then(knob("hitbox", CfConfig.HITBOX_ENABLED,
                        CfConfig.HITBOX_AMOUNT, 0.0, 1.0))
                // No strength: it either fires on a hard stall or it does not. A number here would
                // only invite tuning something that should be a rare, decisive event.
                .then(toggle("release", CfConfig.RELEASE_ENABLED))
                // Off means never commit to a face: the body stays upright and only the
                // forces act. Kept as a switch because it is the one change that alters
                // what the mod fundamentally is.
                .then(toggle("plane", CfConfig.PLANE_ENABLED))
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

    /** {@code <name> enable|disable} for a subsystem with nothing to scale. */
    private static LiteralArgumentBuilder<CommandSourceStack> toggle(
            final String name, final ModConfigSpec.BooleanValue enabled) {

        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    reply(context, label(name).append(onOff(enabled.get())));
                    return 1;
                })
                .then(Commands.literal("enable").executes(context -> {
                    enabled.set(true);
                    enabled.save();
                    reply(context, label(name).append(onOff(true)));
                    return 1;
                }))
                .then(Commands.literal("disable").executes(context -> {
                    enabled.set(false);
                    enabled.save();
                    reply(context, label(name).append(onOff(false)));
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

    /**
     * Restores every value the commands can touch.
     *
     * <p>Reads each default from the config spec rather than from a list written out here, because
     * a list written out here had already drifted once.</p>
     */
    private static int reset(final CommandContext<CommandSourceStack> context) {
        restore(CfConfig.CENTRIFUGAL_ENABLED);
        restore(CfConfig.CENTRIFUGAL_STRENGTH);
        restore(CfConfig.CENTRIFUGAL_LEAD);
        restore(CfConfig.CORIOLIS_STRENGTH);
        restore(CfConfig.MAX_ACCEL_G);

        restore(CfConfig.AIR_ENABLED);
        restore(CfConfig.AIR_STRENGTH);
        restore(CfConfig.AIR_SLIDE);
        restore(CfConfig.AIR_SLIDE_MAX_SPEED);

        restore(CfConfig.GRIP_ENABLED);
        restore(CfConfig.GRIP_STRENGTH);
        restore(CfConfig.GRIP_BRACE_BONUS);
        restore(CfConfig.GRIP_MIN_PRESS_G);
        restore(CfConfig.GRIP_FULL_PRESS_G);
        restore(CfConfig.GRIP_SLIDE_DAMPING);
        restore(CfConfig.GRIP_SLIDE_CAP_G);

        restore(CfConfig.WALL_ENABLED);
        restore(CfConfig.WALL_STRENGTH);
        restore(CfConfig.WALL_MIN_PRESS_G);
        restore(CfConfig.WALL_FULL_PRESS_G);
        restore(CfConfig.WALL_LOOP_ASSIST);
        restore(CfConfig.WALL_MAX_SPEED);

        restore(CfConfig.PLANE_ENABLED);
        restore(CfConfig.PLANE_SWITCH_MARGIN_G);
        restore(CfConfig.PLANE_DWELL_TICKS);
        restore(CfConfig.PLANE_HALF_LIFE);
        restore(CfConfig.PLANE_SLEW_DEG_PER_S);

        restore(CfConfig.RELEASE_ENABLED);
        restore(CfConfig.RELEASE_DECEL_G);
        restore(CfConfig.RELEASE_MIN_SPEED);

        restore(CfConfig.HITBOX_ENABLED);
        restore(CfConfig.HITBOX_AMOUNT);
        restore(CfConfig.HITBOX_MAX_DEG);
        restore(CfConfig.HITBOX_CENTRE_PIVOT);
        restore(CfConfig.HITBOX_HALF_LIFE);
        restore(CfConfig.HITBOX_SLEW_DEG_PER_S);

        restore(CfConfig.CAMERA_ENABLED);
        restore(CfConfig.CAMERA_AMOUNT);
        restore(CfConfig.CAMERA_RESPONSE);
        restore(CfConfig.CAMERA_DAMPING);
        restore(CfConfig.CAMERA_LEAD);
        restore(CfConfig.CAMERA_LEAN);
        restore(CfConfig.CAMERA_LEAN_MAX_DEG);
        restore(CfConfig.CAMERA_PITCH_RESPONSE);
        restore(CfConfig.CAMERA_LOOP_SUPPRESSION);
        restore(CfConfig.CAMERA_MAX_TILT_DEG);
        restore(CfConfig.CAMERA_SLEW_DEG_PER_S);

        reply(context, Component.literal("Reset to defaults.").withStyle(OK));

        return 1;
    }

    private static <T> void restore(final ModConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
        value.save();
    }

    // ---------------------------------------------------------------- status

    private static int status(final CommandContext<CommandSourceStack> context) {
        final ForceState state = CentrifugalHandler.lastState();

        final MutableComponent header = Component.literal("Sable CF ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(state.active
                        ? Component.literal("on a sub-level").withStyle(OK)
                        : Component.literal("not on a sub-level").withStyle(OFF));

        reply(context, header);

        reply(context, join(
                describe("centrifugal", CfConfig.CENTRIFUGAL_ENABLED, CfConfig.CENTRIFUGAL_STRENGTH),
                describe("air", CfConfig.AIR_ENABLED, CfConfig.AIR_STRENGTH),
                describe("grip", CfConfig.GRIP_ENABLED, CfConfig.GRIP_STRENGTH),
                describe("wall", CfConfig.WALL_ENABLED, CfConfig.WALL_STRENGTH)));

        reply(context, join(
                describe("camera", CfConfig.CAMERA_ENABLED, CfConfig.CAMERA_AMOUNT),
                describe("hitbox", CfConfig.HITBOX_ENABLED, CfConfig.HITBOX_AMOUNT),
                label("release").append(onOff(CfConfig.RELEASE_ENABLED.get())),
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
                label("load").append(number(state.tangentialLoad / CfConfig.GRAVITY,
                        state.tangentialLoad <= state.hold ? OK : DANGER)).append(unit(" g")),
                label("hold").append(number(state.hold / CfConfig.GRAVITY, ChatFormatting.WHITE))
                        .append(unit(" g"))));

        // The first line to read when something feels wrong. Near zero means the mod believes no
        // ride is acting on you, so nothing it does should be visible - if you are being moved
        // anyway, it is not this mod.
        reply(context, join(
                label("stick").append(number(state.stick,
                        state.stick > 0.5 ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.WHITE)),
                label("footing").append(number(state.footing,
                        state.footing > 0.0 ? ChatFormatting.WHITE : OFF)),
                label("contacts").append(count(state.contactCount,
                        state.contactCount > 0 ? ChatFormatting.WHITE : OFF)),
                label("plane").append(state.planeIndex >= 0
                        ? Component.literal(FACES[state.planeIndex])
                                .withStyle(ChatFormatting.LIGHT_PURPLE)
                        : Component.literal("none").withStyle(OFF))));

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
                label("body").append(number(state.bodyAngleDeg, ChatFormatting.WHITE))
                        .append(unit(" deg")),
                label("hitbox").append(number(state.hitboxAngleDeg,
                        state.clearanceBlocked ? WARN : ChatFormatting.WHITE)).append(unit(" deg"))));

        final double spin = state.omega.length();
        final double jolt = state.angularAcceleration.length();

        reply(context, join(
                label("spin").append(number(spin, spin > 2.5 ? WARN : ChatFormatting.WHITE))
                        .append(unit(" rad/s")),
                label("gate").append(number(state.spinGate,
                        state.spinGate > 0.0 ? ChatFormatting.WHITE : OFF)),
                label("jolt").append(number(jolt, jolt > 6.0 ? WARN : ChatFormatting.WHITE))
                        .append(unit(" rad/s2"))));

        // Air speed is deliberately deck-relative. On a platform simply travelling at 25 m/s this
        // reads near zero, and that is the fix for being swept off one - if it reads high while you
        // are standing still, the deck is spinning, not merely moving.
        reply(context, join(
                label("air").append(number(state.airSpeed, ChatFormatting.WHITE))
                        .append(unit(" m/s")),
                label("deck").append(number(state.deckVelocity.length(), ChatFormatting.WHITE))
                        .append(unit(" m/s")),
                label("carried").append(number(state.deckTranslation.length(), OFF))
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

    private static MutableComponent count(final int value, final ChatFormatting colour) {
        return Component.literal(Integer.toString(value)).withStyle(colour);
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
