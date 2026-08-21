# Aeronautics Camera Sync mod developer guide

ACS tilts the player's camera to match the surface of the Sable sub-level (Create Aeronautics
contraption) they are standing on. That one sentence has a consequence that reaches into other
mods, which is why this document exists.

**If you read only one paragraph:** ACS moves the point the player actually looks *from*. Most
mods need to do nothing — any ray that goes through `level.clip()` is corrected for you. If your
mod aims at the wrong place while the player stands on a tilting deck, read
[What ACS breaks](#what-acs-breaks-in-your-assumptions). If you need to opt out, or you build
rays a way we cannot see, read [Getting the right ray](#getting-the-right-ray).

---

## Contents

- [What ACS breaks in your assumptions](#what-acs-breaks-in-your-assumptions)
- [Ways to build a ray, and which of them work](#ways-to-build-a-ray-and-which-of-them-work)
- [Getting the right ray](#getting-the-right-ray)
- [Adding the dependency](#adding-the-dependency)
- [Scenarios](#scenarios)
- [Reference](#reference)
- [Diagnosing a problem](#diagnosing-a-problem)

---

## What ACS breaks in your assumptions

### The eye is not where you think it is

When the camera tilts, it rotates around the player's feet. The eye goes with it. So the point
the player aims from is no longer `player.getEyePosition()` — it is that point, rotated. At a
noticeable roll the two are up to a block apart.

### A ray has two halves, and they are tilted by different rules

This is the single most important thing in this document, and the cause of nearly every "ACS
broke my mod" report we have had.

| half of the ray | tilted where |
|---|---|
| **direction** — `getViewVector`, `getLookAngle` | **always, everywhere** |
| **origin** — the eye | only where we manage to correct it |

Take both halves somewhere we do not correct the origin, and you get an untilted start with a
tilted direction: a ray parallel to the correct one and offset from it. It still hits *a* block,
so block interaction looks roughly fine and the bug hides. It stops hiding the moment precision
matters — the real case was a control panel whose buttons could be pressed from a wrong angle and
not from the right one, with the error growing with roll.

If your mod aims wrongly on a deck, this is almost certainly why.

### What already works, with no changes on your side

- **Any ray through `level.clip()` / `BlockGetter#clip`.** We intercept it and move the origin
  into the tilted camera. This covers most mods, including ones that build the eye by hand —
  `position().add(0, getEyeHeight(), 0)` produces bit-for-bit the same point as
  `getEyePosition()`, so it matches too.
- **`Minecraft.getInstance().hitResult`** — the vanilla pick, already corrected.

You do not need this API for either.

### What we cannot see

- **Your own block traversal.** If you walk blocks yourself instead of calling `clip` — Create's
  `RaycastHelper#rayTraceUntil` is the well-known one, and it is what `BigOutlines` uses for rails
  — we never see the ray. Nothing we do can fix it, and no policy will help. You have to ask us
  for the origin: [`AcsState.aimRay`](#acsstate).
- **Reference points that are not rays** — a focus point, a marker, an anchor. Use
  [`AcsState.aimEye`](#acsstate).
- **Your own distance metric.** If you compare distances to decide which hit wins, the numbers
  you compare are yours; we do not touch them.

### ⚠ The player is never actually rotated — and another mod may be rotating them

Everything above is done with the camera and with rays. **ACS does not rotate the player entity.**
To the server, and to every mod that asks vanilla where the player is, they are standing bolt
upright on a flat block. We fake the lean and then drag every "from the eye" ray along with it,
because that is the most a client-side mod can do without desyncing from the server.

So ACS owns one half of an illusion:

| | who owns it |
|---|---|
| what the player **sees** — camera, crosshair, picking, projectiles, thrown items | ACS |
| what the player **is** — model, hitbox, the server's opinion | vanilla, unchanged by us |

This matters for you in two different ways depending on which side you are on.

**If you consume our tilt:** never treat it as the player's real orientation. It is a *camera*
value — smoothed over several frames, and near a wall in first person deliberately scaled down, so
it lags the deck and sometimes deliberately understates it. If what you need is "how is this
contraption oriented", ask Sable for the deck pose; that is where we get it from too. Ours is the
answer to "where is the player looking", nothing more.

**If you rotate the player for real:** you own the other half, and our half then makes two
assumptions that stop being true.

1. Our wall check measures from the *vanilla* eye — straight up in world Y from the feet. For a
   genuinely rotated player that point is a fiction: it swings into the hull while the real eye is
   in open air, we conclude the camera is buried, and the tilt scales to zero near walls. Take the
   duty over with [`disableCameraCollision`](#6-if-your-mod-really-rotates-the-player).
2. We do nothing in third person unless asked, because on our own we would only tilt the view of
   an upright player. If you have rotated them, third person is exactly where your work is visible
   — switch it on with [`enableThirdPerson`](#7-if-your-mod-lives-in-third-person).

**If you are designing your own take on any of this,** the trap to avoid is owning half of each
side. A mod that rotates the model but not the hitbox, or corrects the camera but not the rays,
produces a state that looks correct in the exact scenario it was tested in and wrong everywhere
else — and the two mods then quietly fight, each fixing what the other broke. Decide which half
you own, do that half completely, and declare it through the switches above so the other half can
stand aside. That is what they are for: they are not feature flags, they are a way of saying who
is responsible for what.

### ⚠ A hit on the deck comes back in sub-level coordinates

This one surprises everybody, including us. When your clip hits the contraption rather than the
world, the returned `BlockHitResult` is in the sub-level's **own** coordinate space, which lives
millions of blocks away:

```
player at (-1583, 69, 1346)
hit on the deck:  BlockPos(20481031, 130, 20524045)
a miss:           Vec3(-1580.103, 68.704, 1348.985)     ← world coordinates
```

That is Sable projecting the ray into the contraption's level, not ACS. But it looks like our
bug, and it only shows up when you actually hit the deck — so it survives testing on the ground.
If you need world coordinates, convert with the sub-level's pose
(`Pose3dc#transformPosition`); Sable's API is the reference for that, not ours.

### There is often no tilt at all — and your code should not care

`aim*` values equal `vanilla*` whenever there is nothing to correct: the player turned ACS off,
the config disables it, the camera is in third person, the emergency legacy-pick fallback is on,
a nearby wall has scaled the tilt to zero, or the player is simply not on a deck.

This is deliberate. **Write `state.aimRay(reach)` once and do not branch on it.** A mod that
asks "is there a tilt?" and then takes a different path is a mod with two code paths, one of
which is rarely exercised.

Two subtleties worth knowing:

- **Suppression is not instant.** After a mod suppresses the tilt (see
  [`suppress`](#acshandle)), the camera eases back to level over the normal smoothing time
  instead of snapping. During those frames `suppressed()` is already `true` while `aim*` still
  differs from `vanilla*` — because the camera really is still tilted. For "is there a tilt right
  now", use `tiltApplied()`, never `suppressed()`.
- **Third person with the beta option on.** ACS has a beta setting that allows tilt in third
  person. With it enabled, the direction is tilted but the origin is not — the hybrid described
  above, inside our own mod. It is a known defect scheduled for a later release. Until then,
  `client().firstPerson()` is the flag to check before trusting the `look` pair in third person.

---

## Ways to build a ray, and which of them work

Every row here is a real mod we have looked at.

| how you build it | result |
|---|---|
| `getEyePosition()` + `getViewVector()` + `level.clip()` | works, nothing to do |
| eye assembled by hand + `level.clip()` | works — same point, bit for bit |
| `Sable.HELPER.getEyePositionInterpolated` + `level.clip()` | works |
| `mc.hitResult` | works |
| **your own origin** (not the eye) + `level.clip()` | not corrected → [`AimPolicy.SHIFT`](#aimpolicy) |
| **your own block traversal**, no `clip` at all | invisible to us → [`aimRay`](#acsstate) |
| **a point, not a ray** | invisible to us → [`aimEye`](#acsstate) |
| **physics/suspension** that happens to start at the eye | wrongly corrected → [`AimPolicy.KEEP_VANILLA`](#aimpolicy) |

---

## Getting the right ray

### 1. Do nothing

If your ray goes through `clip` and starts at the eye, stop here. This is the intended outcome
for most mods, and it is why the API is small.

### 2. Ask for the ray

Take a snapshot, read what you need:

```java
AcsState state = ACS.state(player, partialTick);

AcsRay ray = state.aimRay(player.blockInteractionRange());
BlockHitResult hit = level.clip(new ClipContext(
        ray.from(), ray.to(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
```

**Take one snapshot per frame and read from it.** Our state changes *within* a frame — the pick
window opens and closes, wall scaling is recomputed in `Camera#setup`. A mod that asks "am I
tilted?" at one point and "by how much?" at another can get an inconsistent pair, which is the
exact bug class this API exists to remove. A snapshot cannot be inconsistent with itself.

`state()` is a pure read: no clips, no writes, no side effects. Calling it is cheap, but it does
allocate, so per-frame rather than per-question.

### 3. Correct our classification of *your* ray

Some rays go through `clip` but do not start at the eye, so our filter does not recognise them.
Others are not aiming rays at all but happen to start exactly at the eye, so our filter
recognises them wrongly. An `AimPolicy` fixes both:

```java
private static ClipContext myRay = null;

ACS.addPolicy(query -> query.context() == myRay
        ? AimPolicy.Decision.SHIFT
        : AimPolicy.Decision.PASS);

// ... and where you cast it:
ClipContext ctx = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
myRay = ctx;
try {
    return level.clip(ctx);
} finally {
    myRay = null;
}
```

Three rules, and the first is not optional:

- **Recognise your own ray and answer `PASS` to everything else.** Your policy is asked about
  every ray the player casts while tilted — suspension, collision, other mods' aiming. A policy
  that answers for all of them will break the game in ways that look like our bug. Matching on
  the `ClipContext` you created yourself, as above, cannot collide with anyone.
- **No allocations, no raycasts inside `decide`.** It runs from inside `clip`, dozens of times
  per frame. Decide from the fields of the query and return.
- **A policy cannot create a tilt.** If there is no active correction, we never ask. `SHIFT`
  chooses *which* rays move, not *whether* there is anything to move them by.

Mods that register no policy pay nothing: an empty policy list costs one comparison.

### 4. When you want the untilted eye

Two different needs, two different tools.

**For calculations** — `state.vanillaEye()`, `state.vanillaLook(partialTick)`,
`state.vanillaRay(reach)`. These are the values as if ACS were not installed.

**For rendering** — wrap the work instead:

```java
ACS.withVanillaEye(() -> renderNameplate(entity));
int light = ACS.withVanillaEye(() -> computeLight(pos));
```

Inside the scope the player's eye is the real one, and our ray correction is off, so anything
you clip in there is untouched too. This is what lighting probes, entity culling and nameplate
placement want: the true eye, not the aiming point. The scope form cannot be left unbalanced,
and it nests. Client main thread only — from another thread the body still runs, just without
the scope, and we log one warning.

### 5. Asking us to stand aside entirely

If your mod runs a cutscene, a custom camera mode, or a screen where a tilting camera is wrong:

```java
ACS.suppress(3_000);   // milliseconds, extends your own lease if you already hold one
ACS.release();         // drops yours; other mods' leases are untouched
```

It is a lease with an owner, not a switch, so the mod that finishes first cannot un-suppress the
one still running. The tilt eases out and back smoothly rather than snapping — a cutscene that
begins with the camera jerking is a bug report. The clock is real time but stops while the game
is paused, all leases are dropped when the player leaves the world, and leases longer than ten
seconds log a warning with your mod id. On a dedicated server it does nothing (the tilt is
computed client-side) and warns once.

### 6. If your mod really rotates the player

This one is narrow, and it comes with a duty attached.

```java
ACS.disableCameraCollision("rotates the player, keeps its own hitbox");
ACS.enableCameraCollision();   // drops yours; other mods' switches are untouched
```

**The contract: with the check off, you guarantee the camera point is not inside a block.** ACS
checks nothing after that, and seeing through a wall is then your mod's bug, not ours. In the case
we designed this for, the guarantee is a hitbox rotated along with the player.

Why it exists: our check measures from the vanilla eye — straight up in world Y from the feet. For
a player your mod has genuinely rotated, that point is a fiction. It ends up inside the hull while
the real eye is in open air, we conclude the camera is buried in a block, and the whole tilt gets
scaled to zero. You know where the player is and we do not; this is how you say so.

Some details worth knowing:

- **Owned, like a lease.** `enableCameraCollision()` drops only yours, and collision stays off
  while any mod holds the switch.
- **It never expires, and it survives leaving the world.** It states a property of your mod, not
  of the session — register once at startup and it stays. (Suppression leases behave the opposite
  way, and deliberately so: those state something about right now.)
- **`reason` is mandatory and non-blank** — `IllegalArgumentException` otherwise, on both sides.
  It is logged once with your mod id, which is what makes "why does this camera clip through
  blocks" answerable from a log with no reproduction steps.
- **One switch covers all of camera collision**, the same ground the player's `Camera collision`
  setting covers. If you need only half, tell us — we did not split it on speculation.
- Repeat calls are idempotent; no-op on a dedicated server, with one warning.

`client().cameraCollisionDisabled()` and `client().cameraCollisionDisabledBy()` say whether
anyone — including another mod — currently holds it.

### 7. If your mod lives in third person

**By default ACS does nothing in third person.** The camera is vanilla, the crosshair is vanilla,
and every ray leaves the eye exactly where vanilla put it. If your scenario lives back there — a
camera mod, a vehicle view, a cinematic — this is how you ask for the tilt.

```java
ACS.enableThirdPerson("cinematic camera runs in third person");
ACS.disableThirdPerson();   // drops yours; other mods' switches are untouched
```

With it on, third person behaves like first person: the camera is rotated and every ray follows it.

**There is deliberately no way to ask for one half of that.** It is tempting to want the rays
tilted without the camera, and we do not offer it: the tilt quaternion is only computed while the
tilt is actually being applied, so rays would aim along a value nobody is updating. Splitting the
switch would hand you back exactly the crosshair-disagrees-with-the-hit bug this removes.

Details worth knowing:

- **Owned, never expires, survives leaving the world** — same shape as the collision switch above,
  and for the same reason: it states a property of your mod, not of the session.
- **`reason` is mandatory and non-blank**, logged once with your mod id.
- **Read once per frame.** Flip it mid-frame and it takes effect on the next one, never halfway
  through a single pick.
- **No effect under the player's `Legacy pick`** — that fallback predates all of this and stays
  vanilla in third person.
- Repeat calls are idempotent; no-op on a dedicated server, with one warning.

`client().thirdPersonEnabled()` and `client().thirdPersonEnabledBy()` say whether anyone —
including another mod — currently holds it. Note this means `firstPerson()` alone no longer tells
you whether we are correcting anything; check both.

---

## Adding the dependency

```gradle
repositories {
    maven { url = "https://api.modrinth.com/maven" }
}

dependencies {
    compileOnly "maven.modrinth:aero_cam_sync:1.3.7"
}
```

The API was introduced in **1.3.7**. If you need to pin an exact upload rather than a version
number, Modrinth also serves `maven.modrinth:<project-id>:<version-id>` — for this project the
id form is `maven.modrinth:ZGxtWu73:<version-id>`.

Two things that are easy to get wrong:

- **Take the main artifact, not `-slim`.** The main jar carries a bundled library; the slim one
  does not.
- **Sable is not required on your compile classpath.** ACS needs it at runtime, but the API is
  split so that you do not: everything except `AcsClientState.tiltSubLevel()` compiles against
  NeoForge and our jar alone. Only add Sable if you call that one method.

### At runtime, depend softly

Declare the dependency as optional, with an explicit order:

```toml
[[dependencies.yourmod]]
    modId="aero_cam_sync"
    type="optional"
    versionRange="[1.3.7,)"
    ordering="AFTER"
    side="BOTH"
```

`ordering="AFTER"` is not decoration. `type="optional"` only makes us non-mandatory; without an
explicit order, load order is undefined and `ModList.isLoaded` can answer correctly while our API
is not ready yet.

Then check before the first call, and **keep the call in a separate class**:

```java
@Mod(YourMod.MODID)
public class YourMod {
    public YourMod(IEventBus modEventBus) {
        if (ModList.get().isLoaded("aero_cam_sync")) {
            AcsBridge.init();     // the only mention of us, in a class of its own
        }
    }
}
```

```java
public final class AcsBridge {
    private static AcsHandle acs;

    static void init() {
        acs = AeroCamSyncApi.forMod(YourMod.MODID);
        acs.addListener(...);
    }
}
```

The reason for the split is *when* classes load. Java loads a class on first use, not when
something merely references it, so while the branch above is not taken, `AcsBridge` is never
touched and our types are never resolved. Put both in one class and the behaviour gets subtle —
fields and signatures resolve lazily and may not fail, while a method call will. Whether it works
would depend on which line executed, and it would break on the next harmless edit.

There is deliberately no `isPresent()` in this API: if you reached one of our classes, we are
already loaded.

### Compatibility promise

Public is **exactly** the package `com.playsi.aero_cam_sync.api`. Everything else — `ClipNet`,
`PickScope`, `CameraController`, `TiltAccess`, every mixin — is internal and changes without
notice, including in patch releases.

Within `1.x` the signatures in the public package do not break. Anything removed is
`@Deprecated` for at least one minor release first.

---

## Scenarios

**"My gadget/tool clicks the wrong block on a deck."** If it clips, it already works — check
you are on 1.3.7. If it walks blocks itself, use `aimRay`. If it clips from a custom origin, use
`AimPolicy.SHIFT`.

**"My rail/outline preview is offset."** This is the `BigOutlines` case: `rayTraceUntil` never
calls `clip`, so we cannot see it. ❌ A policy will not help here — this is the most common wrong
guess. Ask for the origin with `aimRay` instead.

**"My staff/wand focus point is off."** A point, not a ray: `state.aimEye()`.

**"My suspension/wheel physics went wrong when ACS is installed."** Your probe probably starts
exactly at the eye and we mistook it for aiming. `AimPolicy.KEEP_VANILLA`, matched on your own
context. ❌ Do **not** instead filter by "rays starting within N blocks of the player" — we tried
that ourselves in an older version and it caught exactly this kind of physics; that is the bug
you are reporting, from the other side.

**"I render something from the eye and it looks wrong."** Lighting, nameplates, culling want the
real eye: wrap it in `withVanillaEye`.

**"I need the untilted camera position."** `client().vanillaCameraPos()`. ❌ Do not try to
recover it by inverting the tilt quaternion: the applied tilt is scaled by wall proximity and
that scale changes during the frame, so inverting by the current value does not undo what was
applied. We store the vanilla values instead of reconstructing them, and so should you.

**"My screen/cutscene must not have a tilting camera."** `suppress(millis)`, and remember the
ease-out: check `tiltApplied()`, not `suppressed()`.

**"My mod rotates the player for real, and the tilt dies whenever the vanilla hitbox enters a
block."** Our collision check measures from the vanilla eye, which for a rotated player is not
where the eye is. `disableCameraCollision(reason)` — and read the duty that comes with it in
[If your mod really rotates the player](#6-if-your-mod-really-rotates-the-player). ❌ Do not
reach for `suppress` here: that turns the tilt off, which is the symptom you are trying to fix.

**"I inject into `GameRenderer#pick` and overwrite the hit result."** That still works — we no
longer overwrite anyone's pick result; we set up the conditions and let everyone compute inside
them. Order between us and you is not something either side should rely on.

---

## Reference

Everything public lives in `com.playsi.aero_cam_sync.api`.

### AeroCamSyncApi

```java
static AcsHandle forMod(String modId)
```

One handle per mod id; the same id returns the same object, so a static field is fine. The id is
what appears in our log next to everything you ask us to do — pass your real mod id. Throws
`IllegalArgumentException` on a null or blank id.

### AcsHandle

| method | notes |
|---|---|
| `String modId()` | the id this handle was created for |
| `AcsState state(Player, float partialTick)` | consistent snapshot; pure read; `partialTick` ignored on the server |
| `void suppress(long millis)` | takes or extends **your** lease; no-op on a dedicated server |
| `void release()` | drops **your** lease only |
| `boolean isSuppressed()` | is anyone suppressing |
| `boolean isSuppressedByMe()` | are you |
| `void disableCameraCollision(String reason)` | you take over the duty of keeping the camera out of blocks; `reason` non-blank; survives leaving the world; no-op on a dedicated server |
| `void enableCameraCollision()` | drops **your** switch only |
| `boolean isCameraCollisionDisabled()` | is anyone holding it |
| `boolean isCameraCollisionDisabledByMe()` | are you |
| `void enableThirdPerson(String reason)` | ACS works in third person too — camera and rays together; `reason` non-blank; survives leaving the world; no-op on a dedicated server |
| `void disableThirdPerson()` | drops **your** switch only |
| `boolean isThirdPersonEnabled()` | is anyone holding it |
| `boolean isThirdPersonEnabledByMe()` | are you |
| `void withVanillaEye(Runnable)` | scope with the real eye; client main thread |
| `<T> T withVanillaEye(Supplier<T>)` | same, with a return value |
| `void addListener(TiltListener)` | register once during setup |
| `void addPolicy(AimPolicy)` | register once during setup; read the cost rules above |

### AcsState

Works on both sides.

| method | notes |
|---|---|
| `boolean modEnabled()` | enabled in config and by the player's toggle |
| `boolean tiltApplied()` | a tilt is measurably affecting aim **right now** |
| `boolean suppressed()` / `List<String> suppressedBy()` | who holds a lease; order not guaranteed |
| `Quaternionf posTilt()` / `lookTilt()` | raw rotations, or `null` when that half is not being applied |
| `Vec3 vanillaEye()` / `aimEye()` | |
| `Vec3 vanillaLook(float)` / `aimLook(float)` | recomputed from raw pitch/yaw, so any `partialTick` is valid |
| `AcsRay vanillaRay(double)` / `aimRay(double)` | |
| `AcsClientState client()` | `null` on a dedicated server |

Exactly three things return `null`, and each means "there is no such thing", never "there is no
tilt": `posTilt()`, `lookTilt()`, `client()`.

### AcsClientState

Client only. Reached through `AcsState.client()`.

| method | notes |
|---|---|
| `Vec3 vanillaCameraPos()` / `cameraPos()` | captured once per frame in `Camera#setup`, before we tilt it |
| `Quaternionf vanillaCameraRot()` / `cameraRot()` | same |
| `float tiltScale()` | `1` in the open, falling to `0` with the camera against a wall; stays `1` in third person and when collision is off |
| `boolean firstPerson()` | in third person we do nothing unless a mod switched third person on — check the next row too |
| `boolean legacyPick()` | emergency fallback is on; every `aim*` equals its `vanilla*` |
| `boolean cameraCollisionDisabled()` / `List<String> cameraCollisionDisabledBy()` | who switched collision off through the API; order not guaranteed |
| `boolean thirdPersonEnabled()` / `List<String> thirdPersonEnabledBy()` | who switched third person on; reports who asked, not whether it is in force (`legacyPick()` overrides it) |
| `ClientSubLevel tiltSubLevel()` | the deck the tilt is computed from — **the only method needing Sable on your classpath** |

Camera values are written once per frame, late. Ask earlier in the frame and you get last
frame's values — they are stored rather than reconstructed, for the reason given in
[Scenarios](#scenarios).

### AcsRay

`from()`, `to()`, `direction()` — the shape you put into a `ClipContext`.

### TiltListener

All methods have defaults; override what you need. Events fire on transitions, not per frame:
stepping onto a deck gives exactly one `onTiltStart`.

```java
default void onTiltStart(AcsState state) {}
default void onTiltStop(AcsState state) {}
default void onSuppressionChanged(boolean suppressed, List<String> by) {}
```

`onTiltStop` arrives some frames *after* its cause, when the residual tilt drops below the
threshold — not at the moment the player left the deck.

There are deliberately no sub-level events: which deck the player is on is Sable's business.
The current one is in `client().tiltSubLevel()`.

### AimPolicy and AimQuery

```java
enum Decision { SHIFT, KEEP_VANILLA, PASS }
Decision decide(AimQuery query);
```

`AimQuery` gives you `player()`, `from()`, `to()`, `context()` (nullable) and `startsAtEye()` —
whether our built-in rule matched. It is valid only for the duration of the call; do not keep it.

Policies are asked in registration order and the first non-`PASS` answer wins. If two policies
disagree about the same ray we log both once and take the first.

---

## Diagnosing a problem

**Everything a foreign mod asks of us is logged with a star and the mod id.** In a report that
says "ACS broke my mod", these lines answer who called us at all:

```
[AeroCamSync] * yourmod: api handle acquired
[AeroCamSync] * yourmod: aim policy registered
[AeroCamSync] * yourmod: tilt suppressed for 250 ms
[AeroCamSync] * yourmod: camera collision disabled: rotates the player, keeps its own hitbox
```

Each appears once per session, so the log does not drown.

**Turn on `Debug messages`** in the ACS config for more: which callers our ray net caught, what
your policies decided, and a summary every thirty seconds of how often you called us — useful if
you suspect you are asking for a snapshot far more often than you meant to.

**`Legacy pick`**, also in the config, is the fastest way to answer "is this even ACS?". It
restores the old pipeline; if your symptom changes, we are involved, and if it does not, we are
probably not.

When reporting something to us, the useful attachment is the log with `Debug messages` on, plus
what the player was standing on and whether they were in first or third person.
