# Changelog

Each section is paste-ready for the matching Modrinth version's changelog field.

## 1.2.2 — 2026-08-15

**Critical fix — update from 1.2.0/1.2.1.** Fixes [#1](https://github.com/minerguy341/sable-surefooting/issues/1).

- Fixed dropping an item while standing on a **moving contraption** launching it through the deck or far off into the world, stalling the server for tens of seconds per tick, and spamming `Enormous local sub-level collision bounds, quitting.` into the log. The 1.2.0 code handed the item the contraption's velocity at spawn, asking Sable for it at a **world** position where a sub-level-local one is required — so the tangential term scaled with the distance to the world origin instead of the item's radius on the deck, inflating the velocity by orders of magnitude. Worst on levitating/drag-tuned rigs, which always carry a little angular velocity; harmless on parked ones, which is why it hid for two releases.
- Dropped items now ride a contraption properly instead of being given a velocity and left to fly. A velocity is a straight line, so on a **rotating** deck it always lags behind the curve and the item drifts off the back of the spin. Items are now placed in the contraption's own reference frame on their first tick — the same mechanism that carries you — so they land where they were dropped and keep turning with the deck once they settle, at any spin rate.
- Items keep riding a deck they are resting on for as long as they stay within their exit distance, rather than being handed back and left world-fixed while the platform turns underneath them.
- New `exit_distance_overrides` server option: per-entity-type carry distances, e.g. `["minecraft:item=16.0", "minecraft:experience_orb=16.0"]`, so dropped items and XP can ride much further out than mobs do. Defaults to exactly that.

Found by a follow-up audit of the same code path, and fixed here too:

- Fixed items being pinned to a contraption they were never on. The spawn path fired for items *restored from disk*, not just newly dropped ones — an item entity remembers who threw it, so a stack lying anywhere in the world would be claimed whenever its thrower happened to be riding a contraption. It now applies only to items that are genuinely spawning **and** are actually on the deck.
- **Server owners:** the entity fixes from 1.2.0 onward need the mod installed **on the server**. The Modrinth page previously said the mod was client-side only, which was true up to 1.1.0 but not since.
- Particles no longer get released early. The re-anchor was recorded at the particle's bounding-box centre while Sable measures drift from the particle's own position, leaving a permanent offset that ate into the drift budget — and for larger particles the refresh itself could trigger the release it was meant to prevent.
- Particle anchoring has its own `particle_exit_distance_blocks` (`4.0`) instead of borrowing `exit_distance_blocks`. Turning the latter down for a crisp jump-off used to switch particle anchoring off silently.
- Standing still on a pitching or rolling deck no longer produces a slow sideways push (gravity's own velocity was bleeding into the horizontal rotation).
- Fixed a one-tick velocity snap after editing the config while stood on a spinning contraption, a stale carry surviving a death/respawn on a contraption, `carry_timeout_ticks` lasting one tick longer than set, and stale carry state left behind when an entity was blacklisted mid-carry.
- The Sable and NeoForge dependency ranges are now bounded (`2.0.x`, `21.1.x`). The mod rides on Sable internals, so a future Sable release will now refuse to load cleanly instead of failing mid-tick.

## 1.2.1 — 2026-07-10

**Critical fix — update from 1.1.0/1.2.0.**

- Fixed a crash on **non-rotating** contraptions: standing or jumping on a parked ship or a resting free-floating platform could poison the player's velocity with NaN, freezing them in place and eventually crashing the client. The velocity rotation now skips identity frame-deltas and never writes non-finite velocity.

## 1.2.0 — 2026-07-09

Now covers more than the player:

- **Non-player entities** (item drops, XP orbs, mobs, boats, …) are carried through airborne arcs on moving/rotating contraptions, server-side. Projectiles are deliberately excluded — Sable already gives them launch velocity, and frame-locking would bend their flight.
- **Items dropped while riding a contraption** inherit its velocity at spawn and land where you dropped them.
- **Mobs and armor stands turn with the deck** (`rotate_entity_yaw`) instead of keeping a world-fixed heading while the platform rotates under them.
- **Particles** (smoke, flames, block-break debris) stay anchored to the contraption they spawned on instead of being flung tangentially after half a block of drift.
- New per-world **server config** (`serverconfig/surefooting-server.toml`): carry toggle, rotation strengths, timeout, exit distance, entity blacklist. New `anchor_particles` client option.

The player and particle fixes work client-side even on servers without the mod; the entity carry needs the mod on the server.

## 1.1.0 — 2026-07-09

- New `jump_rotation_strength` option (default 1.16, tuned in-game): compensates the half-tick phase lag of the airborne velocity rotation, keeping cross-jumps accurate however fast the platform spins.
- README: documented engine-side limits (Sable's collision substeps cap reliable tangential speed at ~1 block/tick).

## 1.0.0 — 2026-07-08

Initial release for NeoForge 1.21.1 + Sable 2.0.x.

- Keeps you tracked through jump/fall arcs on moving Sable contraptions, so you land where you took off — including on **rotating** platforms (Sable's stock behavior drops tracking mid-jump and its linear fallback can't represent a rotating frame).
- Rotates your airborne velocity with the contraption's frame: jumps aimed across a spinner go where you aimed (`rotate_jump_velocity`).
- Compensates the sideways (Coriolis-like) pull while walking on fast spinners (`rotate_ground_velocity`, tunable `ground_rotation_strength`).
- Client-side only, no mixins; in-game config screen via the Mods menu.
