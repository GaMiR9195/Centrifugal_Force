---
name: minecraft-gametests
description: >
  Write and run Minecraft GameTests that pass on BOTH Fabric and NeoForge from one common
  codebase (Stonecutter/Architectury projects). Use this whenever the task involves
  writing tests for a mod, verifying mod logic, adding a gametest, fixing a test that
  passes on one loader and fails (or silently doesn't run) on the other, testing with
  mock players, or checking codecs/components/server handlers. Consult it BEFORE writing
  any test class — the per-loader annotation and registration requirements are easy to
  get wrong and fail silently.
---

# Cross-Loader Minecraft GameTests

Patterns proven across 34 gametests in the Thaumaturgy: The New Age project. GameTests
run inside a real dedicated server on each loader (`chiseledGameTest` →
`runGameTestServer` per node), which makes them the definition of green for logic — and
completely blind to client-side bugs (see the end).

## Test class boilerplate (both loaders from one file)

Each loader needs different wiring, expressed with Stonecutter comments:

```java
//? if neoforge {
@net.neoforged.neoforge.gametest.GameTestHolder(NewAgeThaum.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
//?}
public class MyFeatureGameTest {

    //? if neoforge {
    @GameTest(template = "empty")
    //?} else {
    /*@GameTest(template = "new_age_thaum:empty")
    *///?}
    public void somethingBehavesCorrectly(GameTestHelper helper) {
        ...
        helper.succeed();   // forgetting this = timeout failure
    }
}
```

Why the difference: NeoForge ALWAYS prepends the holder's namespace to template names —
`@PrefixGameTestTemplate(false)` + bare `"empty"` is the only working combination.
Fabric wants the fully namespaced id. The shared template itself is a tiny structure
file shipped at `data/<modid>/structure/empty.nbt`.

**Fabric registration is a separate, silent step**: every test class must be listed in
`fabric.mod.json` under the `fabric-gametest` entrypoint. A class missing from that list
compiles fine and simply never runs — the pass count is the only tell. After adding a
test class, check the reported total ("All N required tests passed") went up by the
expected amount on BOTH loaders.

## Assertions

`helper.assertTrue(condition, message)` / `assertFalse`. Put the ACTUAL value in the
message (`"expected 2, got " + actual`) — a bare "assertion failed" on a remote CI run
is useless. End every test with `helper.succeed()`.

## Mock players

`ServerPlayer player = helper.makeMockServerPlayerInLevel();` (deprecated-for-removal,
still the practical option; expect the warning).

**The trap:** mock players fire real `PLAYER_JOIN` events, but have no network channel.
On NeoForge, sending a custom payload to one THROWS; Fabric silently tolerates it — so
a Fabric-only run hides the bug. Every mod network send must be guarded:

```java
if (NetworkManager.canPlayerReceive(player, TYPE)) {
    NetworkManager.sendToPlayer(player, payload);
}
```

Route all sends through one guarded helper so no call site can forget.

## Test the real server path, not a reimplementation

Expose the authoritative server handler (make it `public static` if needed —
e.g. `NewAgeThaumNetwork.applyOrreryEdit(player, blockEntity, cell, aspect)`) and drive
it from the test. Asserting against a test-local copy of the rules proves nothing when
the real handler drifts.

Block entities can be constructed directly for logic tests —
`new MyBlockEntity(BlockPos.ZERO, block.defaultBlockState())` with no level; guard-rails
in the BE (`level == null` checks) make this safe. When the behavior under test is
level-dependent (lazy generation, random sources), attach the test level:
`blockEntity.setLevel(helper.getLevel())`.

## High-value test patterns

- **Property/bulk testing for generators**: seeded `RandomSource.create(fixedSeed)`,
  N runs per configuration, assert invariants that must hold for EVERY output (counts in
  range, forbidden overlaps empty, generated solution actually validates). This is how
  "no unsolvable puzzle can generate" became a tested guarantee instead of a hope.
- **Codec round trips** for anything persisted:
  `CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow(...)` then
  `CODEC.parse(...).getOrThrow(...)`, assert `equals`. Catches field/codec drift the
  moment a record grows a field.
- **Economy/permission tests**: drive accept AND reject branches through the server
  handler; assert rejected operations changed nothing (no state write, no points
  charged).
- **Persistence round trips**: `saveWithFullMetadata` → `BlockEntity.loadStatic` →
  assert the data survived; then remove the item and assert the data traveled with it.

## What gametests cannot catch

Client registration order ("Registry Object not present" on NeoForge), screen factory
registration, rendering, model/texture problems, and anything visual. Fabric + green
gametests is NOT proof a change works — boot the NeoForge client (log marker
"Sound engine started") after touching registrations or client code. See the
`stonecutter-multiloader` skill for the full verification loop.
