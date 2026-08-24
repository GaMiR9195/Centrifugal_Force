---
name: minecraft-mod-config
description: >
  Write config files for Minecraft mods using a dependency-free, hand-rolled flat TOML
  pattern that works identically on Fabric and NeoForge. Use this whenever the user asks
  to "add a config", "make X configurable", "add a config option/setting", mentions a
  config file for a mod, or wants tunable values (visual multipliers, feature toggles,
  colors, sizes) — even if they don't say the word "config". Also use it when converting
  an existing mod config between formats (JSON → TOML) or adding hot-reload to one.
---

# Minecraft Mod Config Files

A pattern for mod configs proven in the Thaumaturgy: The New Age project. The full
reference implementation is in [references/config-template.java](references/config-template.java) —
read it before writing a new config class, then adapt names and fields.

## Why this pattern (and not a config library)

- **Multi-loader common code can't touch loader config APIs.** NeoForge's ModConfigSpec
  doesn't exist on Fabric; Fabric has no standard. A ~60-line hand-rolled parser lives in
  common code and behaves identically on both loaders. No extra dependency to version.
- **TOML over JSON because comments are the entire point.** Users read configs in a text
  editor. Every key gets a comment explaining what it does, its default, and accepted
  values — JSON can't carry those. (Gson also escapes `=` to `=` unless you remember
  `disableHtmlEscaping()`; this bit us in practice. TOML sidesteps the whole class.)
- **Flat `key = value` only.** No tables, no arrays, no nesting. If the config needs
  structure, that's a sign the data belongs in a datapack, not a config file.

## The pattern

1. **Static fields with defaults are the single source of truth.** The field initializer
   IS the default; `apply()` falls back to the current field value on any missing or
   malformed key. There is no separate defaults table to drift out of sync.
2. **`load()` runs first thing in mod init** (before registries), resolving the path via
   Architectury's `Platform.getConfigFolder().resolve("<modid>.toml")`.
3. **Parse leniently, never crash.** A malformed config must never take the game down:
   unknown keys are ignored, unparseable values keep the default, the whole load is
   wrapped in a catch that logs a warning and proceeds on defaults.
4. **Rewrite the file after every load.** This heals missing keys, keeps the doc comments
   current with the code, and means a freshly created file is fully documented from the
   first run. User-set values survive because they were applied before the rewrite.
5. **Hot-reload via mtime, not file watchers.** Store the file's last-modified time at
   load; `maybeReload()` compares and re-loads when it changed. Call it at a natural
   moment — opening the screen the config affects is ideal. Users then tune values with
   the game running: edit, reopen the screen, see the change. No restart, no watcher
   thread, no loader events.
6. **Log the loaded values** at info level in one line — the first question when a config
   "doesn't work" is always "what did it actually load?".
7. **Migrate legacy formats once, then delete the old file** so there is exactly one
   config on disk (see `migrateLegacyJson` in the template).

## Comment style — every key, no exceptions

Each key's comment block answers three questions: what it does, what the default is, and
what values are accepted. Ranges include the effect at the extremes:

```toml
# Gap between sphere cells. 0 = no borders, larger = chunkier.
# Default: 1.0. Accepted: 0.0 to about 3.5 (clamped).
cellBorderWidth = 1.0

# "aspects" = each current blends the colors of its two linked aspects.
# "custom"  = currents use currentBaseColor and the pulse grades
#             currentPulseFrom -> currentPulseTo. Default: "aspects".
currentColorMode = "aspects"
```

## Value conventions

- **Colors** are written as quoted hex strings (`"#8A6BB5"`) but stored as `int` fields;
  parse with `Integer.parseInt(value.replace("#", ""), 16)`, write with
  `String.format("#%06X", rgb)`. Accept both `#RRGGBB` and bare `RRGGBB`.
- **Enum-like choices** are lowercase strings compared with `equalsIgnoreCase`, exposed
  through a small helper (`customCurrentColors()`) so call sites never string-compare.
- **Clamp at the point of use**, not at parse time — the config file keeps the user's
  written value, the game applies a sane range. Comment the clamp range.
- **Booleans and numbers** are written bare (no quotes); strings are quoted.

## Checklist when adding a new config option

1. Add the static field with its default.
2. Add one line to `apply()` (parse with fallback).
3. Add the commented block to `write()` — what / default / accepted.
4. If it affects a screen, confirm `maybeReload()` is called in that screen's `init()`.
5. Mention the option and its effect to the user — they usually want to tune it in-game
   immediately.
