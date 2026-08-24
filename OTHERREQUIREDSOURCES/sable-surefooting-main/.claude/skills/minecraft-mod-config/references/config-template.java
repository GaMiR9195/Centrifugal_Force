// Reference implementation — adapt the class/package/field names to the mod.
// Proven in production (Thaumaturgy: The New Age). Requires only Architectury's
// Platform.getConfigFolder(); on a single-loader project substitute the loader's
// config-dir accessor (FabricLoader.getInstance().getConfigDir() / FMLPaths.CONFIGDIR).
package example.modid.core;

import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Cross-loader config at {@code config/<modid>.toml} with real comments. The format is
 * flat {@code key = value} TOML, read and written by a tiny hand-rolled parser (no extra
 * dependency, works in common code on every loader). The file is rewritten after every
 * load so docs stay current, and {@link #maybeReload()} re-reads it when it changes on
 * disk — edit and reopen the relevant screen, no restart.
 */
public final class ExampleModConfig {
    // Static fields with initializers ARE the defaults — no separate defaults table.
    /** Example toggle. */
    public static boolean exampleToggle = true;
    /** Example multiplier. */
    public static double exampleMultiplier = 1.0;
    /** Example enum-like choice; expose via a helper, never string-compare at call sites. */
    public static String exampleMode = "simple";
    /** Example color, written to the file as "#RRGGBB". */
    public static int exampleColor = 0x8A6BB5;

    private static long loadedModified = -1;

    private ExampleModConfig() {
    }

    public static boolean fancyMode() {
        return "fancy".equalsIgnoreCase(exampleMode);
    }

    private static Path file() {
        return Platform.getConfigFolder().resolve("examplemod.toml");
    }

    /** Call first thing in mod init. Never throws: a broken file logs and uses defaults. */
    public static void load() {
        Path path = file();
        try {
            if (Files.exists(path)) {
                apply(parseFlatToml(Files.readString(path)));
            }
            write(path); // heal missing keys + keep doc comments current
            loadedModified = Files.getLastModifiedTime(path).toMillis();
            ExampleMod.LOGGER.info("Config loaded: exampleToggle={}, exampleMultiplier={}, exampleMode={}",
                    exampleToggle, exampleMultiplier, exampleMode);
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("Could not read config {}; using defaults", path, e);
        }
    }

    /**
     * Cheap mtime check; reloads when the file was edited since the last load.
     * Call at a natural moment — e.g. the init() of the screen the config affects —
     * so users can tune values with the game running.
     */
    public static void maybeReload() {
        try {
            Path path = file();
            if (Files.exists(path) && Files.getLastModifiedTime(path).toMillis() != loadedModified) {
                load();
            }
        } catch (IOException ignored) {
        }
    }

    /** One line per key; every parse falls back to the current (default) value. */
    private static void apply(Map<String, String> values) {
        exampleToggle = parseBool(values.get("exampleToggle"), exampleToggle);
        exampleMultiplier = parseDouble(values.get("exampleMultiplier"), exampleMultiplier);
        if (values.containsKey("exampleMode")) {
            exampleMode = values.get("exampleMode");
        }
        exampleColor = parseHex(values.get("exampleColor"), exampleColor);
    }

    /** Flat {@code key = value} TOML: comments (#), quoted strings, booleans, numbers. */
    private static Map<String, String> parseFlatToml(String content) {
        Map<String, String> values = new HashMap<>();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.startsWith("\"")) {
                int close = value.indexOf('"', 1);
                if (close > 0) {
                    value = value.substring(1, close);
                }
            } else {
                int hash = value.indexOf('#');
                if (hash >= 0) {
                    value = value.substring(0, hash).trim();
                }
            }
            values.put(key, value);
        }
        return values;
    }

    /**
     * Every key gets: what it does, its default, and accepted values (with the effect
     * at the extremes for ranges). This text is the mod's user-facing documentation.
     */
    private static void write(Path path) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Example Mod — config.\n");
        out.append("# Hot-reloads when the <relevant screen> is reopened; no restart needed.\n\n");

        out.append("# What the toggle does, in one or two lines.\n");
        out.append("# Default: true. Accepted: true, false.\n");
        out.append("exampleToggle = ").append(exampleToggle).append("\n\n");

        out.append("# What the multiplier scales. 0 = off, larger = stronger.\n");
        out.append("# Default: 1.0. Accepted: 0.0 or higher (clamped at 4.0 in-game).\n");
        out.append("exampleMultiplier = ").append(exampleMultiplier).append("\n\n");

        out.append("# \"simple\" = <effect>. \"fancy\" = <effect>. Default: \"simple\".\n");
        out.append("exampleMode = \"").append(exampleMode).append("\"\n\n");

        out.append("# Color used for <thing>. Default: \"#8A6BB5\".\n");
        out.append("exampleColor = \"").append(toHex(exampleColor)).append("\"\n");

        Files.createDirectories(path.getParent());
        Files.writeString(path, out.toString());
    }

    private static boolean parseBool(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseHex(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String toHex(int rgb) {
        return String.format("#%06X", rgb);
    }
}
