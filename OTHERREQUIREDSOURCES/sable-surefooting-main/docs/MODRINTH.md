# Modrinth listing kit — paste-ready

## Project setup (Create a project)

| Field | Value |
| --- | --- |
| Name | `Sable: Sure Footing` |
| URL slug | `sable-sure-footing` |
| Visibility | Public |
| Summary | Keep your footing on moving Sable contraptions — jump on a spinning platform and land where you took off. |

## Settings after creation

- **License:** MIT
- **Client-side:** Required · **Server-side:** Optional (the player and particle fixes are client-side and work on any server; the entity fix — item drops, mobs and XP orbs riding a contraption — is server-side and only runs when the server has the mod too)
- **Categories:** Game Mechanics, Transportation, Utility
- **Links:** Source → `https://github.com/minerguy341/sable-surefooting` · Issues → `https://github.com/minerguy341/sable-surefooting/issues`
- **Dependencies:** [Sable](https://modrinth.com/mod/sable) — Required. (Create / Create: Aeronautics are *not* dependencies — anything that moves a Sable sub-level benefits.)

## Changelogs

Per-version changelog text lives in [CHANGELOG.md](../CHANGELOG.md) — paste the matching section into each Modrinth version's changelog field (Modrinth renders the same markdown).

## First version upload

- File: `build/libs/sable-surefooting-1.0.0.jar`
- Version number: `1.0.0` · Channel: Release
- Loaders: NeoForge · Game versions: 1.21.1

## Description (paste into the editor)

---

Ever jumped on a moving [Sable](https://modrinth.com/mod/sable) contraption (Create: Aeronautics airship, spinning bearing platform, moving vehicle) and landed somewhere you didn't expect — or overboard?

**Sable: Sure Footing** keeps you in the contraption's reference frame while you jump and walk:

- 🦘 **Jump in place** on a moving or spinning platform → land exactly where you took off.
- 🏃 **Run and jump across** a rotating platform → your jump goes where you aimed, exactly as if the platform were stationary.
- 🚶 **Walk on a fast spinner** without the sideways (Coriolis) pull dragging you off the edge.
- 🪂 Deliberately jumping *off* a contraption still releases you cleanly — no rubber-banding, no being dragged along.

### Why does this happen?

Sable carries entities standing on a contraption by warping them with it every tick ("tracking"), but tracking drops near the apex of a jump and the fallback is a straight-line velocity snapshot — which can't represent a rotating frame. The faster the rotation, the worse the drift. Sure Footing keeps you tracked through the whole airborne arc and rotates your velocity with the contraption's frame, using only Sable's public API — **no mixins**, fully client-side, safe to add or remove at any time.

### Config (in-game via the Mods screen, or `config/surefooting-client.toml`)

| Option | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Master toggle |
| `rotate_jump_velocity` | `true` | Aim jumps in the rotating frame |
| `rotate_ground_velocity` | `true` | Cancel the sideways pull while walking |
| `ground_rotation_strength` | `2.25` | How aggressively to compensate while walking; lower toward 1.0 if you curl into the spin |
| `carry_timeout_ticks` | `60` | Max airborne ticks to stay in the contraption's frame |
| `exit_distance_blocks` | `4.0` | Release distance when leaving the contraption |
| `particle_exit_distance_blocks` | `4.0` | Release distance for anchored particles |
| `debug_logging` | `false` | Per-tick telemetry for bug reports |

### Compatibility

Requires Sable 2.0.x on NeoForge 1.21.1. Works with anything that moves Sable sub-levels, including Create: Aeronautics. You can join any server with it: the player and particle fixes are client-side. Installing it on the server as well additionally fixes item drops, mobs and XP orbs on moving contraptions.

---

## Gallery suggestions

1. Screenshot of a player mid-jump over a spinning bearing platform (caption: "Jump on a spinner, land on the same block").
2. Your swivel-bearing test rig with the speedometer (caption: "Validated at 5+ m/s tangential speed").
3. Before/after comparison if you record one — a short GIF of jumping in place with the mod off vs on sells it instantly.
