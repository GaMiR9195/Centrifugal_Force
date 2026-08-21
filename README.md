# Sable: Centrifugal Force (`sable_cf`)

Rotating-frame physics for [Sable](https://modrinth.com/mod/sable) sub-levels on Minecraft **1.21.1 / NeoForge**.

A contraption that spins fast enough should be able to hold you against its wall the way a real
rotor ride does, air moving past you at 25 m/s should try to peel you off it, and the camera should
lean like a person rather than lock to the floor plane. That is the whole mod.

## What it does

**Rotating-frame forces.** The centrifugal, Euler and Coriolis accelerations of the sub-level you
are tracking are added to your velocity. Centrifugal is `omega^2 * r`, so a drum of radius 5 blocks
needs about 2.5 rad/s - roughly 24 rpm - to beat gravity, which is genuinely what a real rotor ride
spins at. Past that point apparent gravity points at the wall, and the wall behaves like a floor.

**Air drag against static friction.** Drag is `g * (v / reference_speed)^2` computed from your speed
*through the air*, which on a spinner is dominated by the deck's own tangential speed. Friction can
hold `friction * normal_load` of tangential force and no more; only the excess actually moves you.
So you stand, or slide slowly, or slide fast, or leave - and which one is arithmetic, not a state
machine. Sneak to brace and hold on longer.

Both grow with spin rate, but drag grows as `(omega*r)^2` while the press grows only as
`omega^2 * r`. Drag always wins eventually. Getting swept off a fast enough ride is not a feature,
it is a consequence.

**A camera that follows felt gravity, not the deck plane.** That single choice is the whole
difference from deck-locking:

| situation | apparent gravity | what the camera does |
|---|---|---|
| level deck | straight down | nothing at all |
| gentle list | still nearly down | barely moves - you are on a slope and it looks like it |
| banked turn | down + lateral | motorcycle lean, proportional to the turn |
| fast drum | outward, huge | rolls all the way to the wall |
| slow 360 flip | gravity wins | the world rotates around you |
| fast 360 flip | centrifugal wins | the camera goes round with it |

No thresholds anywhere. Pitch is damped separately from roll (pitch is the nauseating axis, roll is
well tolerated), yaw is dropped, and the result runs through a critically damped spring - which is
the exact mathematical answer to "snappy and smooth at the same time": fastest possible approach
with zero overshoot.

**Debug arrows.** Flat, camera-facing, with 2D triangular heads, plus a numeric readout.

## Building

Java 21 (what 1.21.1 / NeoForge 21.1 runs on).

1. **Copy the Gradle wrapper in.** It is not committed here. Take `gradlew`, `gradlew.bat` and the
   `gradle/wrapper/` folder from `OTHERREQUIREDSOURCES/sable-surefooting-main/` - same NeoForge
   version, same ModDevGradle plugin version, so it is already the right one.

2. **Put an ACS jar in `libs/`** if you want the camera. `AcsHandle#addTiltSource()` only exists on
   the **api-beta** line of Aeronautics Camera Sync, which is newer than the published 1.3.7. Build
   a jar from `OTHERREQUIREDSOURCES/Aeronautics_Camera_Sync-api-beta/` and drop it in as
   `libs/aero_cam_sync-api-beta.jar`. `build.gradle` picks it up automatically.

   With an empty `libs/` the build falls back to `maven.modrinth:aero_cam_sync:1.3.7` and
   `client/CfTiltSource.java` will **not** compile, because that method is not in 1.3.7.

3. `./gradlew build` - jar lands in `build/libs/`.

Sable and Sable Companion come from `https://maven.ryanhcode.dev/releases` and are `compileOnly`;
at runtime they come from your mods folder.

### Running it

Required: Sable `[2.0.0,2.1)`. Optional: Aeronautics Camera Sync (camera only), Sure Footing
(recommended - see below). The mod loads and runs without either; you just lose that feature.

## Commands

All client-side, all live, all saved.

```
/sable_cf status
/sable_cf debug_overlay [true|false]

/sable_cf centrifugal_force enable | disable
/sable_cf centrifugal_force strength <0..4>          scale on the physical value

/sable_cf air_resistance enable | disable
/sable_cf air_resistance reference_speed <2..200>    m/s at which drag equals 1 g

/sable_cf grip friction <0..4>                       static friction, the stand/slide knob

/sable_cf camera enable | disable
/sable_cf camera response <1..40>                    spring frequency, rad/s
/sable_cf camera pitch_response <0..1>               how much pitch to apply vs roll
/sable_cf camera deck_lean <0..1>                    blend towards the deck normal
/sable_cf camera max_tilt <0..90>                    degrees
```

`reference_speed` is deliberately a speed, not a multiplier: it is the air speed at which drag
equals gravity. Because the law is quadratic, halving it makes drag four times stronger.

Everything else - Euler and Coriolis scaling, brace bonus, the safety clamp, arrow scales, smoothing
half-life - is in the config file and on the Mods screen Config button.

## Things worth knowing

**Sure Footing is not called, on purpose.** It already keeps you in the sub-level's frame through a
jump arc, which is the inherited-inertia half of the job. Duplicating that would double-count. Run
both; they compose.

**Sable already hands over momentum when tracking ends.** `sable$getInheritedVelocity()` is shown in
the debug readout for exactly that reason - it is why this mod never adds the deck's velocity by
hand when you are flung. Doing both would count it twice.

**Collision has a substep budget.** Sable steps entity/sub-level collision a bounded number of times
per tick, so past roughly one block per tick - about 20 m/s of *relative* speed - contacts get
unreliable and you can tunnel. That is an upstream limit, not something this mod can fix. Big rides
want a big radius and a moderate rpm rather than a small radius spun stupidly fast.

**Fall damage is the server's opinion.** This mod resets the client's fall distance while you are
pressed hard into a surface, but the server keeps its own count from position deltas. For testing
rides, `/gamerule fallDamage false`.

**No mixins.** Sable is reached through `Sable.HELPER` plus two duck interfaces, and all of it is in
`compat/SableAccess.java` - one file to check when Sable moves. Those two interfaces are
`@ApiStatus.Internal`, which is why the dependency is pinned to `[2.0.0,2.1)` rather than left open.
ACS is used only through its published `api` package. See `docs/UPSTREAM.md` for the handful of
small API additions that would let this mod stop touching anything internal at all - and for the one
feature (rotating the player's hitbox) that is genuinely not possible from out here today.

## Layout

```
CfConfig.java                    every number, with the reasoning
compat/SableAccess.java          every line of Sable this mod touches
physics/FrameSample.java         omega and alpha from the pose delta
physics/CentrifugalHandler.java  the physics tick
physics/CfMath.java              quaternion log/exp
client/CfTiltSource.java         where the camera points
client/TiltSpring.java           critically damped rotational spring
client/AcsBridge.java            every line of ACS this mod touches
client/DebugArrows.java          flat billboarded arrows
client/DebugHud.java             the numbers
command/CfCommands.java          /sable_cf
```

MIT.
