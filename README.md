# Sable Centrifugal Force

Centrifugal force, air drag and grip for [Sable](https://modrinth.com/mod/sable) sub-levels, so a
spinning contraption is something you can stand on, slide down, hold on to, or be thrown off.

Minecraft 1.21.1 / NeoForge, Java 21. Mod id `sable_cf`.

---

## What it actually does

A rotating platform in Sable moves you, but nothing about it *pushes* you. This adds the physics
that makes rides feel like rides:

- **Centrifugal, Euler and Coriolis force**, derived from Sable's own velocity field rather than
  from a guessed rotation centre, so a washing-machine drum can pin you to its wall and a fast
  360-flip can let you walk on the inside of the loop.
- **Air drag** that scales with your true speed through the air - including the deck's tangential
  speed, which is what a spinner actually throws you with.
- **Grip**: an explicit friction budget. You stand, you slide in a controllable way, or you lose
  footing entirely, and which one you get is one comparison rather than three special cases.
- **A hitbox that rotates with the deck**, not just a rotated model.
- **A camera** that tracks felt gravity instead of the deck plane, and that treats loops, sharp
  banks and ordinary walking as three separate problems.

---

## Commands

Every force takes its strength directly. There are no sub-parameters to combine in your head.

```
/sable_cf                                  # same as status
/sable_cf status
/sable_cf debug_overlay [true|false]       # no argument toggles

/sable_cf centrifugal_force enable | disable | <0..4>
/sable_cf air_resistance    enable | disable | <0..4>
/sable_cf grip              enable | disable | <0..4>
/sable_cf camera            enable | disable | <0..2>
/sable_cf hitbox            enable | disable | <0..1>

/sable_cf reset
```

`1.0` is the tuned default everywhere. Passing a number also enables that subsystem - typing a
strength and getting no effect is never what you meant.

Status colours are fixed so they can be read at a glance while you are being thrown around: grey
labels, aqua for a value you set, green on / dark grey off, and measured values that run
white -> yellow -> red as you approach losing your footing. `load` is green while friction can hold
it and red once it cannot, which is the one comparison that decides whether you stay on.

Everything else - Coriolis strength, brace bonus, press thresholds, camera internals, arrow scales -
lives in the config file and in the Mods -> Config screen. It is deliberately not in the command
tree; a command surface you have to scroll is not usable mid-ride.

---

## The config, and where the balance sits

Common config: `config/sable_cf-common.toml`. **Common, not client** - see below.

| Section | Knob | Default | Notes |
|---|---|---|---|
| `centrifugal_force` | `strength` | `1.0` | Scales all frame acceleration |
| | `coriolis_strength` | `0.35` | Damped on purpose - full Coriolis fights your own walking |
| | `max_accel_g` | `8.0` | Safety clamp for a teleporting contraption |
| `air_resistance` | `strength` | `1.0` | |
| `grip` | `strength` | `0.85` | Friction coefficient |
| | `brace_bonus` | `1.9` | Sneak to hold on |
| | `min_press_g` | `0.2` | Below this a surface is not footing |
| | `full_press_g` | `1.2` | Press at which the body tilts fully |
| `hitbox` | `amount` | `1.0` | `0` keeps the box upright; the model still leans |
| `camera` | `amount`, `response`, `damping` | `1.0`, `9.0`, `1.0` | Damping `1.0` is critical |
| | `jolt_gain` | `1.6` | Extra stiffness from a sharp manoeuvre |
| | `loop_suppression` | `0.85` | How much a full loop is ignored |
| | `walk_damping` | `0.65` | Extra damping while walking |
| `debug` | `alpha`, `min_length`, `max_length` | `0.7`, `0.28`, `3.5` | |

**Drag is deliberately softer than reality.** True aerodynamic drag is quadratic, and quadratic drag
in a game means you are fine and then abruptly you are not, with no band in between where you can
fight it. The exponent here is `1.35` and the result is soft-capped at about `2.2 g`, which keeps the
shape of the real curve - faster still hurts more, always - while leaving a wide speed range where
holding on and sliding under control are both real options. Raise `air_resistance` above `1.0` if you
want it closer to honest.

Gravity is `32 m/s^2`, not `9.81`. Minecraft's own gravity is about `32 m/s^2`, and mixing SI gravity
into a game that uses its own would make every threshold read wrong.

---

## Two things to know before you build

**1. Camera tilt needs an unreleased ACS API.** `AcsHandle#addTiltSource` exists only on the
`api-beta` branch of Aeronautics Camera Sync, not in the published `1.3.7`. Build that branch and
drop the jar in `libs/`, or the camera stays off - the bridge catches `LinkageError` and everything
else keeps working. The physics, the rotated hitbox and the debug overlay do not need ACS at all.

**2. `neoforge.mods.toml` says `0.2.0`, `gradle.properties` says `mod_version=0.1.0`.** The version is
a literal in the toml on purpose, because nothing guarantees the build expands `${mod_version}`
there, and a mod that fails to load over a token is a bad trade. Bump both together.

Build with `./gradlew build`. Two hard requirements at runtime: Sable `[2.0.0,2.1)` and
Minecraft `[1.21.1,1.22)`.

---

## Design notes worth reading

### The body frame is computed on both sides, and nothing is sent

`BodyFrame` derives orientation, surface normal, apparent gravity and frame acceleration from
Sable's pose - which both the client and the server already have, and already agree on. So both
sides reach the same answer without a single packet. That is why the config is **common**: a hitbox
only the client believes in is worse than no hitbox change at all, because the server keeps testing
an upright box, decides you are inside a block, and shoves you out.

### The floor-to-wall transition is a blend, not a switch

`SurfaceEstimator` blends the six candidate deck axes with a softmax on how well each aligns with
felt-down. Picking the best axis outright is what makes a wall-ride snap: at the halfway point the
winner flips and your orientation changes in one tick. With a softmax, two axes that score equally
produce their bisector, so the transition sweeps through the intermediate angles - the requested
"riding up a rounded slope" rather than a switch.

Tilt is also partial, scaled by how hard you are pressed (`min_press_g` to `full_press_g`). At low
press you lean a little towards the surface; only real force stands you up fully on the wall. That is
the drum-ride feel: you are not quite standing on it, but with enough spin you are.

### Sharp manoeuvres key off angular acceleration, not centrifugal magnitude

Centrifugal magnitude is `omega^2 * r`. It grows with radius and with steady spin, neither of which
is a manoeuvre - which is why the camera response used to be unpredictable: standing at the rim of a
steadily rotating platform produced a big number while nothing was happening, and a genuinely sharp
flick near the axis produced a small one. Angular acceleration is large exactly when the ride changes
what it is doing. The response curve is `x / (1 + x)`: monotonic, bounded, no knee to fall off.

### Loops are detected by the horizontal component of angular velocity

A banked turn rotates about a vertical axis and should still lean. A flip or a loop rotates about a
horizontal one, and following felt-down through it rolls the camera a full 360 degrees, which is the
most nauseating thing a camera can do and not what a person does either. That one projection is the
whole discriminator, and during a loop the target fades to a running average of felt-up whose window
tracks the revolution period - averaged over a revolution, the centrifugal part cancels and real
gravity is what is left.

---

## Known limitations

- **The hitbox grows while the body is part-way tilted.** A rotated box has to be re-fitted to an
  axis-aligned one, and at 45 degrees a 0.6 x 1.8 player fits a box about 1.7 blocks wide. In a
  one-block corridor on a tilting deck you can wedge. Lower `hitbox.amount`, or set it to `0` and
  keep the visual lean only.
- **The eye position is not offset along the body's up axis.** Your view stays at vanilla eye height
  even when your body is on its side, so lying against a drum wall the camera sits slightly inside
  the body. This needs an upstream hook - see `docs/UPSTREAM.md`.
- **The player *model* is not rotated**, only the hitbox and the camera. Sable's renderer applies
  yaw only.
- **Debug arrows are occluded by world geometry.** They are drawn in front of the player model, which
  was the actual problem, and they are genuinely alpha-blended - but drawing through terrain needs a
  render type with `NO_DEPTH_TEST`, and building one needs an access transformer on
  `RenderType.create`. Not worth a build-level change for a cosmetic debug feature.
- **Sable resolves collisions in a limited number of substeps** (about 8), so beyond roughly 20 m/s of
  surface speed you can tunnel regardless of anything here.
- **Fall damage.** Being pressed into a surface resets fall distance above `1.5 g`, but a ride that
  flings you will still hurt on landing. `/gamerule fallDamage false` while testing.
- **Two mixins.** `EntityMixin` on `makeBoundingBox` and `EntitySubLevelUtilMixin` on Sable's
  `getCustomEntityOrientation`. Both are unavoidable and both are as narrow as possible - see
  `docs/UPSTREAM.md` for what would remove them.
