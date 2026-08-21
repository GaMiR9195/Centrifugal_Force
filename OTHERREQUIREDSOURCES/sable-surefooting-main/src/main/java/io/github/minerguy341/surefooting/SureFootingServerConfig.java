package io.github.minerguy341.surefooting;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server-side settings: carrying non-player entities is server-authoritative, so these live in a
 * SERVER config (per-world, synced) rather than the client config.
 */
public final class SureFootingServerConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue CARRY_ENTITIES;
    public static final ModConfigSpec.DoubleValue ENTITY_JUMP_ROTATION_STRENGTH;
    public static final ModConfigSpec.DoubleValue ENTITY_GROUND_ROTATION_STRENGTH;
    public static final ModConfigSpec.BooleanValue ROTATE_ENTITY_YAW;
    public static final ModConfigSpec.IntValue CARRY_TIMEOUT_TICKS;
    public static final ModConfigSpec.DoubleValue EXIT_DISTANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXIT_DISTANCE_OVERRIDES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CARRY_BLACKLIST;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        CARRY_ENTITIES = builder
                .comment("Keep non-player entities (item drops, XP orbs, mobs, boats, ...) moving with the " +
                        "Sable sub-level they are on while they are airborne, so they don't get left behind " +
                        "or flung off fast contraptions. Players are handled client-side; projectiles are " +
                        "excluded (Sable already gives them launch velocity, and locking them to the frame " +
                        "would bend their trajectories).")
                .define("carry_entities", true);
        ENTITY_JUMP_ROTATION_STRENGTH = builder
                .comment("Multiplier on the airborne velocity rotation for carried entities. Same default and " +
                        "meaning as the client's jump_rotation_strength, but this one also accepts 0.0 to " +
                        "switch the rotation off (the client has no such option and floors at 0.5).")
                .defineInRange("entity_jump_rotation_strength", 1.16, 0.0, 3.0);
        ENTITY_GROUND_ROTATION_STRENGTH = builder
                .comment("Multiplier on the grounded velocity rotation for carried entities (mobs walking on " +
                        "spinning platforms). Mirrors the client's ground_rotation_strength.")
                .defineInRange("entity_ground_rotation_strength", 2.25, 0.0, 3.0);
        ROTATE_ENTITY_YAW = builder
                .comment("Turn carried entities (mobs, armor stands, ...) with the sub-level so they keep " +
                        "facing the same direction relative to it, instead of keeping a world-fixed heading " +
                        "while the deck rotates under them.")
                .define("rotate_entity_yaw", true);
        CARRY_TIMEOUT_TICKS = builder
                .comment("Maximum number of ticks an entity stays in a sub-level's reference frame while airborne.")
                .defineInRange("carry_timeout_ticks", 60, 1, 1200);
        EXIT_DISTANCE = builder
                .comment("Default: stop carrying an entity once it is this many blocks outside the sub-level's " +
                        "bounding box. Used for any entity type not listed in exit_distance_overrides. " +
                        "(Players have their own client-side exit_distance_blocks — set that to 0 to drop off " +
                        "the moment you step off an edge.)")
                .defineInRange("exit_distance_blocks", 4.0, 0.0, 64.0);
        EXIT_DISTANCE_OVERRIDES = builder
                .comment("Per-entity-type carry distances, overriding the default above. Each entry is " +
                        "\"<entity id>=<distance>\", e.g. [\"minecraft:item=16.0\", \"minecraft:experience_orb=16.0\"] " +
                        "to let dropped items and XP ride much further from the contraption than mobs do. " +
                        "Items also keep riding a deck they are resting on for as long as they stay inside " +
                        "this distance, so raising it keeps them aboard further out.")
                .defineListAllowEmpty("exit_distance_overrides",
                        List.of("minecraft:item=16.0", "minecraft:experience_orb=16.0"),
                        () -> "minecraft:item=16.0", SureFootingServerConfig::isValidOverride);
        CARRY_BLACKLIST = builder
                .comment("Entity type ids that should never be carried, e.g. [\"minecraft:boat\", \"examplemod:drone\"].")
                .defineListAllowEmpty("carry_blacklist", List.of(), () -> "", o -> o instanceof String);

        SPEC = builder.build();
    }

    /** Accepts "&lt;id&gt;=&lt;number&gt;" entries for the exit-distance override list. */
    private static boolean isValidOverride(final Object o) {
        if (!(o instanceof final String s)) {
            return false;
        }

        final int eq = s.lastIndexOf('=');
        if (eq <= 0 || eq == s.length() - 1) {
            return false;
        }

        try {
            Double.parseDouble(s.substring(eq + 1).trim());
            return true;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    private SureFootingServerConfig() {
    }
}
