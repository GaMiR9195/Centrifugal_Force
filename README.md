# Sable Centrifugal Force

Rotating-frame physics for [Sable](https://maven.ryanhcode.dev) sub-levels on **Minecraft 1.21.1 /
NeoForge**. A spinning contraption pins you to its wall, pushes you outward, lets you walk up the
lip, and eventually sweeps you off - and the camera leans the way a person's head would rather than
bolting itself to the deck plane.

The reference test is a rotor ride: a drum that spins fast enough to hold riders against the inside
wall. If that works, everything here works.

---

## Build

1. **Copy the Gradle wrapper.** No binaries are committed. Take `gradlew`, `gradlew.bat` and
   `gradle/wrapper/` from `OTHERREQUIREDSOURCES/sable-surefooting-main/` - same NeoForge line, same
   ModDevGradle 2.0.141, drop-in compatible.
2. **Build the ACS API jar into `libs/`.** Compile
   `OTHERREQUIREDSOURCES/Aeronautics_Camera_Sync-api-beta/` and put the jar at
   `libs/aero_cam_sync-api-beta.jar`. `build.gradle` picks up `libs/aero_cam_sync*.jar`
   automatically. **Required to compile the camera** - see [Camera](#camera).
3. **Add the icon.** Put a PNG at `src/main/resources/icon.png`. See [Icon](#icon).
4. `./gradlew build` - jar lands in `build/libs/`. **Java 21.**

The version lives only in `gradle.properties` (`mod_version`); `processResources` expands it into
`neoforge.mods.toml`.

### Icon

`logoFile = "icon.png"`, and the file belongs at **`src/main/resources/icon.png`** - path relative
to the resources root, not to `META-INF`.

**WebP does not work.** Minecraft reads PNG through its own STB-backed loader and has no WebP
decoder anywhere in the asset pipeline; the mod-list logo goes through the same path. Convert the
image to PNG. There is deliberately no fallback: a fallback would hide a filename typo forever, and
the failure mode of a missing logo is one warning line and a blank square.

Square, 128x128 or 256x256, is what the mod list expects.

---

## Commands

Every subsystem is one knob: `enable`, `disable`, or a number - and the number turns it on, because
typing a strength and getting no effect is not what anyone meant by it.

```
/sable_cf                            status (bare command, the thing you want most often)
/sable_cf status
/sable_cf debug_overlay [true|false]

/sable_cf centrifugal_force  enable | disable | <0..4>
/sable_cf air_resistance     enable | disable | <0..4>
/sable_cf grip               enable | disable | <0..4>
/sable_cf slip               enable | disable | <0..2>
/sable_cf camera             enable | disable | <0..2>
/sable_cf hitbox             enable | disable | <0..1>
/sable_cf release            enable | disable
/sable_cf reset
```

Everything else - Coriolis scale, brace bonus, attach thresholds, rim climb, camera shaping, arrow
scales - is in the config screen or `config/sable_cf-common.toml`. Live, no restart.

`reset` reads defaults out of the config spec itself rather than from a list in the command code, so
it cannot drift out of date.

### Tuning order

Each knob answers a different question. Turn them in this order:

| Knob | Question |
| --- | --- |
| `grip` | do I stand or slide |
| `slip` | how fast do I creep outward |
| `air_resistance` | when do I get swept off entirely |
| `centrifugal_force` | how strong is the ride at all |
| `camera` | how much does the view lean |

When something feels wrong in a way you cannot name, read **`share`** in the overlay first.

---

## How it works

### The one number that matters: `share`

`share` is the fraction of the force pressing you into a surface that comes from **the ride** rather
than from gravity or your own movement.

It exists because the real failure mode of a mod like this is doing something when nothing is
happening. A platform that is merely travelling - a lift, a ship under way - accelerates, wobbles,
and delivers a pose over the network that is interpolated on arrival. Differentiate that pose twice
and you get several m/s^2 of pure noise from a contraption doing nothing. Feed that into a tilt and a
drag term and the player gets nudged and tipped while standing still on an ordinary moving deck.

Three structural guards, not tuning values:

- **Frame acceleration is low-passed, then dead-zoned.** Below `FRAME_ACCEL_DEADZONE_G` (0.16 g) it
  is exactly zero, faded in above so there is no step at the boundary. Nothing an ordinary platform
  does reaches it.
- **Tilt is gated on `share`, not on acceleration.** Gravity presses you down at 1 g on any deck;
  only a ride adds to that. `share` near zero means no tilt whatever the raw numbers look like.
- **Rotation-only features are gated on spin rate.** Wall attach, outward slip and rim climb do not
  exist below `SPIN_DEADZONE` (0.15 rad/s). A lift cannot trip them however it is thrown about -
  that is a property of the code, not a threshold someone picked.

On a plain moving sub-level `share` reads near zero, `contacts` finds the floor, and the arithmetic
is identical to standing on the ground.

### Contact detection

Six probes, one per local axis of the sub-level, each querying real block shapes just outside the
body. This replaced picking whichever of the six axes was most opposed to felt-down - a guess with no
way of being wrong, because it always returned a surface. An airborne player got a phantom floor
normal, a tilt, and a shove.

Because the probes are real contacts:

- Detection is **not limited to the floor plane**; walls and ceilings are contacts like any other.
- Simultaneous contacts blend (`SURFACE_BLEND_SHARPNESS`), so floor-to-wall inside a drum passes
  through intermediate angles instead of snapping when a winner changes.
- No contact means no normal, no press, no tilt. Being in the air is representable.

A raycast is not an option: hits against a sub-level come back in sub-level coordinates, millions of
blocks away.

### Air drag is deck-relative

```
air = (deck point velocity - sub-level rigid translation) + your own velocity - wind
```

Subtracting the rigid translation is the whole fix for being swept off ordinary platforms. A deck
cruising at 25 m/s is **not** a 25 m/s headwind for someone standing on it, and treating it as one is
precisely what used to shove players around. What survives the subtraction is what should: the
deck's **rotation** (omega x r) and your own walking. A spinner still tries to peel you off; a lift
does nothing.

The law is superlinear (exponent 1.35), soft-capped near 2.2 g, rather than quadratic. Quadratic drag
does nothing until a knee and then removes all control at once, which reads as "nothing, nothing,
thrown off". At 1.35 there is a wide band where sliding is a state you can steer against for a few
seconds - shifted toward gameplay, still honest that speed hurts.

### Grip, slip and the drum

One Coulomb comparison, not a tree of thresholds: friction holds `grip * press`, and only the excess
tangential load moves you. Stand, creep, slide, get swept - one formula, so transitions are
continuous and "sliding" is a real state you can fight. Sneaking multiplies friction by
`brace_bonus` (1.9), so resisting is a choice.

On top of that, three things that exist only on a spinning ride:

- **Outward slip.** A fraction (`slip`, 0.35) of the surface-tangential centrifugal load is let past
  friction as deliberate creep, capped at `slip.max_speed` (3.2 m/s). In a drum you ease from the
  middle out to the rim and end up leaning on the lip. Slow enough to walk against, so it reads as
  pressure rather than a rail.
- **Wall attach.** Touching a wall sideways latches you to it when the ride is genuinely pinning you:
  `attach_press_g` (0.75 g) with hysteresis down to `release_press_g` (0.45 g), **and**
  `attach_share` >= 0.6. The share test is the "only in the right scenarios" rule - a drum flinging
  you outward passes it easily; running into a wall on a calm contraption cannot. Ordinary walls stay
  ordinary and stay bounce-off-able. **Jumping always releases the latch**, unconditionally, so you
  are never trapped in something you want out of.
- **Rim climb.** Above `rim_climb_g` (1.35 g) an outward-facing obstruction becomes climbable at
  `rim_climb_speed` (2.6 m/s), like a step. Explicitly **not** driven by the tilt of the physics -
  being pressed into a wall hard enough to walk up it is a consequence of the press, which is also
  why a rider in a real rotor can walk up the wall. Below the threshold the lip is a wall and stops
  you.

`adhesion_g` (0.35) is small and does not hold you up - friction does. It exists because Sable
resolves entity/sub-level contact in a limited number of substeps, so a body exactly touching a
moving wall drifts a hair off it and back every tick, making "stuck to the drum" a coin flip.
Raising it will not help you stick; it will only make letting go feel sticky.

### Hitbox

The mod supplies an **orientation** and nothing else, through Sable's own
`getCustomEntityOrientation` hook. Sable builds an oriented box from the entity's unrotated size plus
that quaternion and runs SAT against it.

**There is no AABB inflation, and there should never have been.** An earlier version also overrode
`Entity#makeBoundingBox` to fit the rotated box inside a wider axis-aligned one. That was wrong in
both directions: Sable never consults the axis-aligned box for sub-level collision, so it bought
nothing there, while vanilla collision *does*, so the widened box wedged players against ordinary
main-level blocks in corridors. Rotating a body does not require making it bigger. The override is
deleted.

Worth knowing, from reading Sable: it already expands its consideration bounds by `getEyeHeight()`
and pivots the oriented box about eye height rather than the feet, and `collide()` early-returns for
`ServerPlayer`.

**Partial tilt.** `tiltFromPress` is a smoothstep from `grip.min_press_g` (0.2) to
`grip.full_press_g` (1.2), multiplied by a ride weight that is itself a smoothstep of `share` over
0.15..0.6. Light press gives a slight lean toward the surface; full alignment needs real force. 1.2 g
is reachable at ride speeds, not absurd ones - deliberately toward gameplay. `hitbox` scales the
whole thing, down to 0 for camera-only.

### Camera

Registered as an ACS `TiltSource` at priority 100. The target is the **felt** down direction, not the
deck plane - that single substitution is what makes a level deck do nothing, a slope look like a
slope, and a fast drum carry you round.

The chain, in order, exists to be gentle without being dead:

1. **Low-pass on the target** (`smoothing`, 0.12 s half-life) - removes pose noise before it can
   become motion.
2. **Dead-band** (`deadband_deg`, 0.7 degrees) - slop, so micro-jitter produces literally no camera
   movement rather than a small amount of constant movement. This is what kills the shakes.
3. **Spring** on the rotation vector (`response` 4.5, `damping` 1.05) - near-critically damped:
   fastest approach with no overshoot, which is the mathematical answer to "snappy and smooth at the
   same time".
4. **Jolt boost** - `gain * x / (1 + x)`, monotonic and bounded, where `x` mixes the turn rate of the
   felt-up direction with angular acceleration. So changes of direction are *felt* - stiffer spring
   for the moment of the turn - but the response cannot spike. The old trigger was omega^2 * r, which
   grows with radius and with steady rotation, so standing on the rim gave a big number while nothing
   was happening and a sharp flick near the axis gave a small one. That was the instability.
5. **Loop suppression** (0.85) - full flips fall back to a running mean of felt-up over the rotation
   period, so the centrifugal part cancels across a revolution and the camera stops rolling with a
   360.
6. **Walk damping** (0.6) and full removal of the Coriolis term - Coriolis is the only component
   generated by *your* movement rather than the ride, so simply walking on a spinning deck no longer
   tips the view. "Walking" and "holding a bank" are damped separately.
7. **Pitch response** 0.45, **deck lean** 0.2, max tilt 65 degrees, slew 200 deg/s. Pitch is the
   nauseating channel and is damped harder than roll; yaw is discarded entirely. `deck_lean` is a
   pinch of the actual surface normal as proprioception - at 1.0 it would reproduce exactly the
   deck-locked feel this was built to avoid.

Note for anyone extending this: **never call `AcsHandle#state()` inside a `TiltSource`** - it
re-enters the source and stack-overflows.

### Launch through the centre

Optional (`release`, on by default). If a contraption is carrying you upward and the sub-level itself
is suddenly stopped - it catches on a frame, hits a ceiling - you keep going and fly out through the
opening.

Detection uses the **raw**, unfiltered frame acceleration (this is the one place where the sharp
signal is the point): deceleration along the direction of travel above `decel_g` (9.0) while moving
faster than `min_speed` (3.5 m/s). It fires **only if you were already attached or gripped**, with a
10-tick cooldown. So it cannot turn into bouncing off every contraption you brush against - if the
ride never had hold of you, there is nothing to be released from.

### Debug overlay

`/sable_cf debug_overlay`. Billboard arrows with flat 2D triangular heads - velocity, centrifugal,
drag, felt-down, surface normal - plus a text block.

Three length bands: below the deadzone there is no arrow at all, above it never shorter than
`min_length` (0.28), then length follows magnitude up to `max_length` (3.5). Smoothing is on the raw
physics vectors with a 70 ms half-life on real time, so thresholds are in m/s and g rather than in
pixels. Alpha comes from `debug.alpha` (0.7); `debugQuads` already carries translucent transparency.

Geometry is biased toward the camera (`CAMERA_BIAS` 0.35) so arrows draw over the player model.
**Honest limit:** world geometry still occludes them. Real depth-test-off needs `RenderType.create`,
which is `protected static` and therefore an access transformer; `RenderSystem.disableDepthTest()` by
hand does not survive, because the render type reinstates its own depth shard in
`setupRenderState()` when the batch flushes.

The HUD's most useful lines are `press / hold / load` - you can watch load cross hold at the instant
you start sliding - and `share / contacts / attached`.

---

## Known limits

- **Eye position does not shift along body-up.** Lying against a drum wall the camera sits slightly
  inside the body. Needs an upstream hook.
- **The player model does not rotate** - only the collision box and the camera. Sable's renderer
  applies yaw only.
- **Sable resolves collision in a limited number of substeps** (about 8 for the local player), so
  above roughly 20 m/s of relative surface speed contacts get unreliable and tunnelling is possible.
  This is upstream, not this mod. Practical consequence: build a **large** ride at moderate rpm
  rather than a small drum at insane rpm.
- **Gravity here is 32 m/s^2**, Minecraft's own, not 9.81. Every `g` in the config and overlay uses
  that; mixing in SI would make all the thresholds read wrong.
- **Fall damage is the server's opinion.** The mod clears client `fallDistance` above 1.5 g of press,
  but the server keeps its own count from position deltas. For testing rides:
  `/gamerule fallDamage false`.
- **The camera needs the unreleased ACS API.** Without `libs/aero_cam_sync*.jar` the build fails on
  `CfTiltSource`; at runtime against released 1.3.7 the bridge catches `LinkageError`, logs, and
  disables the camera while physics and arrows keep working.
- **Sure Footing is untouched** - no compile dependency, no calls. It already keeps your jump arc in
  the sub-level frame, which is exactly the half of the job it should own; duplicating it would count
  the impulse twice. Likewise Sable already supplies inherited velocity on release, so the mod does
  not add deck velocity by hand.

The only two places touching Sable internals are collected in `compat/SableAccess.java` - one file to
fix when Sable moves. That is also why the dependency is pinned to `[2.0.0,2.1)` rather than open.

---

## Upstream

`docs/UPSTREAM.md` holds the requests for Sable, Sure Footing and ACS, each with the reasoning and
the code path that forced it - ready to paste into an issue.
