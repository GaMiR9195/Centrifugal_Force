---
name: minecraft-ui-design
description: >
  Design and implement Minecraft mod GUIs: container screens, menus, custom widgets,
  scrollable lists, tooltips, drag-and-drop, custom 2D/3D rendering inside a screen, and
  the loader-specific registration traps that silently break them. Use this whenever the
  user wants a screen, GUI, UI, menu, HUD overlay, or any in-game interface for a mod
  (1.20+, Fabric or NeoForge, Architectury or not) — including "make a block open a UI",
  "add a slot/inventory screen", "render X in the GUI", or fixing a screen that won't
  open or renders wrong. Even a "simple" screen hits these traps; consult this first.
---

# Minecraft UI Design (1.21.x, multi-loader)

Patterns and traps proven in the Thaumaturgy: The New Age project (Arcane Orrery: a
420x240 container screen with a scrollable list, real item slots, drag-and-drop painting
onto a rotatable 3D sphere, and animated energy currents). For custom geometry rendering
(Tesselator, projection, culling, animation) read
[references/gui-3d-rendering.md](references/gui-3d-rendering.md) when the screen needs
more than sprites and text.

## Architecture: menu + screen + server authority

For any screen attached to a block or item inventory, use the vanilla container triad —
it buys slot sync, shift-click, and persistence for free:

- **`AbstractContainerMenu`** with real `Slot`s backed by the block entity's `Container`.
  Item contents then sync via vanilla slot logic — no custom packets for the inventory.
  Implement `quickMoveStack` (players will shift-click immediately).
- **`AbstractContainerScreen`** for rendering. In 1.21.1 `Screen.renderables` is private:
  draw panel chrome in `renderBg`, text in `renderLabels` (its pose is already translated
  to leftPos/topPos — draw at slot-relative coordinates). Buttons go through
  `addRenderableWidget` in `init()`.
- **Extra opening data** (e.g. the BlockPos) via Architectury `MenuRegistry.ofExtended` +
  `openExtendedMenu`, read back in the client menu constructor from the `FriendlyByteBuf`.
- **Edits are optimistic client-side, authoritative server-side.** The client mutates its
  view immediately (snappy) and sends a small C2S payload; the server validates
  (reach, permissions, costs) and on rejection calls
  `player.containerMenu.broadcastFullState()` — vanilla resync rolls the client back
  without custom rollback code.

## The registration-timing trap (NeoForge)

Menu screen factories have exactly one safe registration window on NeoForge, and missing
it fails in two different silent ways:

- Client init is **too early**: `RegistrySupplier.get()` throws "Registry Object not
  present" (registries not populated yet during construction).
- CLIENT_SETUP is **too late**: `RegisterMenuScreensEvent` already fired, so the screen
  factory is never seen — right-click does nothing, log says "Failed to create screen
  for menu type".

The fix: register from the supplier's own callback, which fires exactly when the menu
type registers: `MENU_SUPPLIER.listen(type -> MenuRegistry.registerScreenFactory(type, Screen::new))`.
Related: never call `RegistrySupplier.get()` during mod construction anywhere (item
`Properties.component(...)`, color handler args — use the `Supplier` overloads). Fabric
tolerates all of this, and gametests never run client code, so **the only verification
that counts is booting the NeoForge client** (ready marker in the log: "Sound engine
started").

## Feedback rules (the difference between "broken" and "polished")

- **A silent no-op reads as a bug.** Every rejected interaction needs an audible or
  visible response — a `VILLAGER_NO` via `SimpleSoundInstance.forUI` tells the player
  "understood, refused" instead of "the mod is broken". This came directly from a
  playtest report ("I can't place aspects" — the action was correctly refused, silently).
- **Pitch-vary repeated sounds per subject** so interactions feel alive:
  `0.9f + Math.floorMod(thing.hashCode(), 6) * 0.08f` gives each item a stable chime.
- **Preview consequences before commit**: while dragging, show on the hover target what
  the drop would do (e.g. white rim = valid, grey rim = allowed but inert). Compute it
  with the same rule code the server uses, never a reimplementation.
- **Disabled ≠ hidden.** Grey out inert entries (blend toward the panel grey) but keep
  them listed; show zero-counts dimmed rather than removing rows, so the layout is stable
  and the player learns what exists.
- **State the empty state.** If the screen needs an item inserted to function, draw a
  hint line saying so; never present a dead UI.

## Widget patterns

- **Scrollable list**: `enableScissor`/`disableScissor` around the rows; wheel scroll AND
  a draggable scrollbar. Give the scrollbar its own gutter column — rows overlapping the
  bar was a real playtest complaint (couldn't grab it). Thumb size
  `max(12, listH * listH / contentH)`.
- **Tooltips**: `renderComponentTooltip` on hover with a `List<Component>`; build lines
  with `Component.translatable` + `ChatFormatting` styles. Everything user-visible goes
  through lang keys from day one.
- **Drag-and-drop**: track the payload in a field; `mouseClicked` picks up (return true to
  claim the click), `mouseDragged` just returns true while holding, `mouseReleased` drops
  and clears. Render the payload as a swatch at the cursor in `render()` after
  `super.render`.
- **Toggle buttons** re-label via `setMessage` on click (`Camera: Free` ⇄ `Camera: Lock`)
  rather than spawning separate buttons.

## Layout conventions that held up

- Fixed-size panel (e.g. 420x240) centered by `AbstractContainerScreen`; compute all
  regions in `init()` from `leftPos`/`topPos`, never hardcode absolutes.
- Title bar strip at top, functional buttons top-right, hint text bottom-center under the
  main viewport.
- Dark-purple UI palette pattern: panel fill `0xF0100A18`, chrome `0xFF241B33`, inset
  wells near-black, primary text `0xE8D9FF`, secondary `0x9A8CBF`, dimmed `0x5F5876`.

## Verification loop

After any UI change: `chiseledBuild` + gametests for the logic, then boot the NeoForge
client in the background, watch the log for "Sound engine started" vs crash markers, and
hand off to the user for the visual check — rendering bugs (invisible geometry, wrong
alpha, culled faces) produce zero log output and only a human screenshot catches them.
