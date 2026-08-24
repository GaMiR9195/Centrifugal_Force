---
name: minecraft-data-components
description: >
  Data components, codecs, and network payloads for modern Minecraft mods (1.20.5+,
  Fabric/NeoForge via Architectury). Use this whenever the task involves storing data on
  an ItemStack (research, charges, ownership, puzzle state — any per-item data), writing
  or evolving a Codec/StreamCodec, adding a custom network packet (C2S or S2C), syncing
  server state to clients, or debugging "component didn't persist / didn't sync / old
  items lost their data" problems. Consult it BEFORE defining any new record that gets
  serialized — codec-evolution and registration-timing mistakes are expensive to unwind.
---

# Data Components, Codecs & Networking (1.21.x, Architectury)

Patterns proven in the Thaumaturgy: The New Age project (wand components, research-sphere
data, generated-puzzle components, five custom payloads).

## Design principle: data lives ON the item

If a workstation edits per-item state (research on a paper, charge in a tool), store it
as a component on the ItemStack, not in the block entity. The block entity holds the
stack; the data automatically travels, persists, drops, and stacks correctly. Vanilla
slot sync then carries component changes to clients for free — no custom packet needed
for menu-visible state.

## Defining a component

```java
public static final RegistrySupplier<DataComponentType<MyData>> MY_DATA =
        COMPONENTS.register("my_data", () -> DataComponentType.<MyData>builder()
                .persistent(MyData.CODEC)                 // NBT/disk
                .networkSynchronized(MyData.STREAM_CODEC) // play-time sync
                .build());
```

- Register through a `DeferredRegister<DataComponentType<?>>` on
  `Registries.DATA_COMPONENT_TYPE`, init'd before items.
- **Never bake a component default into `Item.Properties().component(TYPE.get(), ...)`**
  — the eager `.get()` crashes NeoForge at construction ("Registry Object not present").
  Use `getOrDefault` with an `EMPTY` constant at read sites instead.
- Access pattern: `stack.get(TYPE.get())` (nullable), `stack.getOrDefault(TYPE.get(),
  MyData.EMPTY)`, `stack.set(...)`, `stack.has(...)`.

## The component record

Immutable `record` with defensive copies and no-op-aware copiers:

```java
public record MyData(int frequency, Map<Integer, ResourceLocation> entries, boolean sealed) {
    public MyData {
        entries = Map.copyOf(entries);
    }
    public MyData(int frequency, Map<Integer, ResourceLocation> entries) {
        this(frequency, entries, false);      // convenience ctor = old shape
    }
    public MyData asSealed() {
        return sealed ? this : new MyData(frequency, entries, true);
    }
}
```

## Codecs (disk format)

`RecordCodecBuilder.create(instance -> instance.group(...).apply(instance, MyData::new))`
with these field patterns:

- **Evolving a record without breaking saved data**: add new fields as
  `Codec.BOOL.optionalFieldOf("sealed", false)` — items serialized before the field
  existed load with the default. This is the ONLY safe way to grow a shipped component.
- **Integer-keyed maps**: JSON/NBT map keys must be strings —
  `Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), V.CODEC)`.
- **Sets**: `Codec.INT.listOf().<Set<Integer>>xmap(HashSet::new, List::copyOf)`.
- **Validated ranges**: `Codec.intRange(1, 8).fieldOf("frequency")`.
- Test every codec with a round trip (encode via `NbtOps.INSTANCE`, parse, assert
  equals) in a gametest — it catches codec/record drift the moment a field is added.

## StreamCodecs (network format)

For multi-field records, a hand-rolled pair is clearer than composing:

```java
public static final StreamCodec<RegistryFriendlyByteBuf, MyData> STREAM_CODEC =
        StreamCodec.of(MyData::write, MyData::read);
```

Write counts + entries with `writeVarInt`, collections with
`buf.writeCollection(set, FriendlyByteBuf::writeVarInt)`. **Contravariance gotcha**: the
method references must be `FriendlyByteBuf::` even when the buf is a
`RegistryFriendlyByteBuf` — `RegistryFriendlyByteBuf::writeVarInt` does not compile.
Keep write and read in the same order, field for field, and append new fields at the end.

## Network payloads (Architectury NetworkManager)

Registration is asymmetric and getting it wrong crashes dedicated servers:

```java
if (Platform.getEnvironment() == Env.CLIENT) {
    NetworkManager.registerReceiver(NetworkManager.s2c(), TYPE, STREAM_CODEC, handler);
} else {
    NetworkManager.registerS2CPayloadType(TYPE, STREAM_CODEC);  // send-only registration
}
// C2S: register on BOTH sides (client needs the type to send); handler runs server-side.
NetworkManager.registerReceiver(NetworkManager.c2s(), TYPE, STREAM_CODEC,
        (payload, context) -> context.queue(() -> handle(payload, context))); // game thread!
```

- `context.queue(...)` moves the handler onto the game thread — never touch level/player
  state directly from the netty thread.
- **Guard every send**: `if (NetworkManager.canPlayerReceive(player, TYPE))` — vanilla
  clients and gametest mock players have no channel; NeoForge throws on unguarded sends
  (Fabric tolerates, hiding the bug). One guarded `sendIfPossible` helper, used
  everywhere.
- Sync registries/state to each player on `PlayerEvent.PLAYER_JOIN`.

## Server-authoritative edits with optimistic clients

For interactive UIs: the client mutates its local view immediately (snappy UX) and sends
a small C2S request; the server is the only authority:

1. Validate everything server-side: player reach (`distanceToSqr` against the block),
   registry existence of ids from the wire, permissions/locks, costs.
2. Apply and sync on success.
3. On rejection, `player.containerMenu.broadcastFullState()` — vanilla resync rolls the
   client's optimistic change back with zero custom rollback code.
4. Make the authoritative handler `public static` and drive it directly from gametests
   (accept AND reject branches; assert rejects change nothing).
