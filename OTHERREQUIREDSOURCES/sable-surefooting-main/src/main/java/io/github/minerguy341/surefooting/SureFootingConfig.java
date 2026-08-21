package io.github.minerguy341.surefooting;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SureFootingConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue ROTATE_JUMP_VELOCITY;
    public static final ModConfigSpec.BooleanValue ROTATE_GROUND_VELOCITY;
    public static final ModConfigSpec.DoubleValue JUMP_ROTATION_STRENGTH;
    public static final ModConfigSpec.DoubleValue GROUND_ROTATION_STRENGTH;
    public static final ModConfigSpec.IntValue CARRY_TIMEOUT_TICKS;
    public static final ModConfigSpec.DoubleValue EXIT_DISTANCE;
    public static final ModConfigSpec.BooleanValue ANCHOR_PARTICLES;
    public static final ModConfigSpec.DoubleValue PARTICLE_EXIT_DISTANCE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLED = builder
                .comment("Keep moving with a Sable sub-level while jumping so you land where you took off.")
                .define("enabled", true);
        ROTATE_JUMP_VELOCITY = builder
                .comment("While airborne over a rotating sub-level, rotate your velocity with the sub-level's frame " +
                        "so a jump aimed at a spot on the platform lands on that spot. Without this, your jump " +
                        "direction stays fixed in world space while the platform turns away under you.")
                .define("rotate_jump_velocity", true);
        ROTATE_GROUND_VELOCITY = builder
                .comment("Also rotate your velocity with the sub-level's frame while walking on it, cancelling " +
                        "most of the sideways (Coriolis-like) pull you feel when moving on a fast-spinning platform. " +
                        "Your retained momentum otherwise stays fixed in world space each tick while the platform " +
                        "rotates under you, so every step drifts toward the trailing side.")
                .define("rotate_ground_velocity", true);
        JUMP_ROTATION_STRENGTH = builder
                .comment("Multiplier on the airborne velocity rotation. 1.0 aligns your velocity to the frame at " +
                        "each tick's end, which still trails the rotation slightly while the velocity is applied; " +
                        "values above 1.0 add lead so cross-jumps stay accurate however fast the platform spins. " +
                        "The default was tuned in-game (pure theory says 1.5; practice landed lower). " +
                        "Only affects airborne movement.")
                .defineInRange("jump_rotation_strength", 1.16, 0.5, 3.0);
        GROUND_ROTATION_STRENGTH = builder
                .comment("Multiplier on the grounded velocity rotation. 1.0 rotates your momentum exactly with the " +
                        "frame; values above 1.0 over-rotate to also compensate the per-step lag (each step executes " +
                        "as a straight world-space line while the platform keeps turning mid-step). If you still " +
                        "drift toward the trailing side on a fast spinner, raise this; if you curl into the spin, " +
                        "lower it. Only affects grounded movement.")
                .defineInRange("ground_rotation_strength", 2.25, 0.0, 3.0);
        CARRY_TIMEOUT_TICKS = builder
                .comment("Maximum number of ticks to stay in a sub-level's reference frame while airborne. " +
                        "A vanilla jump takes ~12 ticks; the timeout only matters for long falls.")
                .defineInRange("carry_timeout_ticks", 60, 1, 1200);
        EXIT_DISTANCE = builder
                .comment("Stop moving with the sub-level once you are this many blocks outside its bounding box, " +
                        "so deliberately jumping off a contraption doesn't drag you along.")
                .defineInRange("exit_distance_blocks", 4.0, 0.0, 64.0);
        ANCHOR_PARTICLES = builder
                .comment("Keep particles (smoke, flames, block breaking, ...) anchored to the sub-level they " +
                        "spawned on for as long as they stay near it. Sable normally releases a particle once " +
                        "it drifts half a block, flinging it tangentially on fast contraptions.")
                .define("anchor_particles", true);
        PARTICLE_EXIT_DISTANCE = builder
                .comment("Stop re-anchoring a particle once it is this many blocks outside the sub-level's " +
                        "bounding box, after which Sable releases it normally. Separate from " +
                        "exit_distance_blocks: that one is about when YOU stop being carried, and tuning it " +
                        "down for a crisp jump-off would otherwise silently switch particle anchoring off.")
                .defineInRange("particle_exit_distance_blocks", 4.0, 0.0, 64.0);
        DEBUG_LOGGING = builder
                .comment("Log carry transitions and per-jump landing offsets for debugging.")
                .define("debug_logging", false);

        SPEC = builder.build();
    }

    private SureFootingConfig() {
    }
}
