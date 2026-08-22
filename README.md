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
/sable_cf grip               enable | disable | <0..8>
/sable_cf wall               enable | disable | <0..2>
/sable_cf camera             enable | disable | <0..2>
/sable_cf hitbox             enable | disable | <0..1>
/sable_cf release            enable | disable
/sable_cf plane              enable | disable
/sable_cf reset
```

Everything else - Coriolis scale, brace bonus, press ramps, plane dwell, camera shaping, arrow
scales - is in the config screen or `config/sable_cf-common.toml`. Live, no restart.

`reset` reads defaults out of the config spec itself rather than from a list in the command code, so
it cannot drift out of date.

### Tuning order

Each knob answers a different question. Turn them in this order:

| Knob | Question |
| --- | --- |
| `wall` | how much press does it take before I am a wall-walker |
| `grip` | do I stand or slide once I am one |
| `air_resistance` | how much does the wind drag me along the surface |
| `centrifugal_force` | how strong is the ride at all |
| `camera` | how much does the view follow |

When something feels wrong in a way you cannot name, read **`stick`** in the overlay first.

---

## The model: wall-walking, not a force field

The mod has one idea in it. **Past enough centrifugal press you are standing on the wall** - hitbox
and visual both - and the only thing that stops you simply welding yourself to it is air resistance.

Everything else follows from that sentence:

- if you are standing on the wall, gravity does not drag you along it, so the tangential load is
  cancelled in proportion to how much of a wall-walker you are;
- if you are standing on the wall, you have grip, so the wind has to beat friction before you move;
- but the wind always gets a share, so on a 360 platform you creep - slowly, at a fixed terminal
  speed, in a direction you can walk against. You can ride the loop all the way over; you just never
  get to stand perfectly still while doing it.

One number expresses it: **`stick`**, 0 to 1.

```
stick = ramp(ride press, wall.min_press_g .. wall.full_press_g) * spin gate
```

`stick` is the single input to the hitbox angle, the camera angle and the force cancellation. They
cannot disagree with each other, because they are three readings of one decision rather than three
decisions that have to be kept in step. This is the structural change in this release: the previous
build derived the body tilt, the camera target and the wall assist independently, which is why they
drifted apart in corners and fought each other in transitions.

### `stick` is deliberately hard to earn

It is gated on **ride press only** - the press that the rotating frame is supplying, not the total
press. Gravity pressing you into an ordinary floor produces exactly zero, at any strength, forever.
That is why the mod does nothing at all on a static deck, and it is a property of the definition
rather than of a threshold that could be mistuned.

The spin gate multiplies it to zero below `spin.deadzone`, so a sub-level that merely translates -
an airship, a lift, a train - can never produce it either.

---

## What is measured, and how

### Frame kinematics

`FrameKinematics` differentiates the sub-level's pose once, in the deck frame, and only once:

- **omega** from the rotation between consecutive poses, converted through the quaternion logarithm
  so a 359-degree step reads as -1 rather than +359;
- **alpha** from the change in omega;
- **linear acceleration** from the pivot's motion.

The centripetal, Euler and Coriolis terms are then evaluated *analytically* from omega, alpha and
your offset from the pivot.

This is the fix for the lag you could see in the arrows. The old build computed the frame
acceleration by **differentiating position twice** - and its velocity input was itself a backward
difference, so the result was centred a tick and a half in the past and then rotated by the *current*
pose, adding a further minus-delta-theta of yaw. At 2.5 rad/s that is 7 to 15 degrees of sideways
error, which is exactly what "the arrows point a bit to the side" looks like. An analytic
`omega x (omega x r)` has no lag by construction: it is evaluated at your position, this tick, from a
quantity that only needed one derivative.

`centrifugal_force.lead_ticks` compensates the remaining half-tick of filter delay. It defaults to 1.

### Contact detection

Six thin slabs, one just outside each face of your oriented box, tested against real block collision
shapes in the sub-level's own local coordinates. No raycast, no inference from felt-down. A face
reports a contact when something solid is actually there.

### Plane commitment: hysteresis, not a blend

The old build blended the six axis normals with a softmax. In a floor-wall corner that produces a
**diagonal** normal that belongs to neither surface, and the wall assist then projected gravity onto
it and pushed you up and sideways along it - the "it lifts me up" report, generated by an averaged
normal that no real surface had.

A surface is now a discrete choice with hysteresis:

- one face is **committed** and is the floor;
- a challenger must beat it by `plane.switch_margin_g` for `plane.dwell_ticks` consecutive ticks
  before it takes over;
- until then the committed face stays, unrotated and unblended.

So the plane changes decisively or not at all. Corners resolve to one surface, transitions happen at
a definite moment, and there is no orientation that averages two faces.

The rotation for a committed face is `rotationTo(world up, normal)`, which is continuous through a
full 360 loop - the axis flips sign exactly when the angle passes 180, and `Rz(-t)` and
`Rz(360-t)` are the same rotation. The one degenerate case, a normal antiparallel to world up, is
resolved with the previous tick's axis instead of an arbitrary one, so a loop does not roll the
player sideways as it passes through inverted.

---

## Hitbox

The body rotates towards `identity slerp plane, stick`, rate-limited by `hitbox.slew_deg_per_s` and
smoothed with a half-life. The collision orientation is handed to Sable through
`EntitySubLevelUtil.getCustomEntityOrientation`, which builds an oriented box and runs SAT against
the sub-level's blocks.

### Why it would not turn on a "solnyshko"

The old gate was a product of three ramps, and one of them was `1 - feltUp.y` against a floor of
0.04. On a big wheel at 1 rad/s and 5 m radius, felt-down deviates from true down by about 0.012 -
**below the floor** - so the product was exactly zero and the hitbox never moved, no matter how
obviously the world was rotating. The same gate also demanded 0.8 g of press before it started,
which at the top of a loop means about 1.8 g of ride, since gravity is subtracting there.

Both are gone. The hitbox follows `stick`, which is about press and spin - the two things that
actually determine whether you are a wall-walker - and knows nothing about how far felt-down has
moved. Full inversion works.

The false positives on ordinary sub-levels came from the other end of the same expression: second
derivatives of an interpolated network pose are noisy, and noise that clears a deadzone is
indistinguishable from signal. With acceleration now analytic and the plane committed with dwell,
there is nothing for the noise to trip.

### The pivot, which is what was shaking you

Sable rotates the oriented box about a point at **eye height**:

```
offset = (0, eyeHeight - ysize/2, 0)     // 0.72 for a standing player
center.add(offset).sub(R.transform(offset))
```

The box centre therefore sweeps `2 * 0.72 * sin(A/2)`: 0.13 blocks at 10 degrees, **1.02 at 90**,
1.44 at 180. That is a cartwheel, not a lean, and it swings the box straight into the surface it is
leaning towards. Sable then resolves the penetration it just created, and its near-vertical branch
redirects the whole MTV along the body up axis at full length:

```
if (dot > 0.8) { entityUp.mul(maxMTV.dot(entityUp), maxMTV).normalize(preLength); }
```

A metre of penetration comes back as a metre-long shove pointing up and out, eight substeps per
tick. That is the lifting and the shaking, and it was produced before any force in this mod was
consulted - which is why no amount of tuning ever removed it.

`SubLevelEntityCollisionMixin` cancels that method, so the box rotates about its own centre. Checked
against every use of the pivot in Sable: it is applied in exactly one place; the
`fma(+eyeHeight, up_old)` / `fma(-eyeHeight, up_new)` pair inside the substep loop cancels exactly,
because this mod returns one orientation for every partial tick; and `getFeetPos` is only consumed
as a difference under the same rotation. Removing it is well-defined, and over a full 360 the
accumulated displacement is **zero** - which is what makes the loop possible.

Compensating the player's position instead was considered and rejected: it needs
`(I - R) * (0, eyeHeight, 0)`, between 1.02 and 2.29 blocks of real displacement, which moves the
vanilla AABB, the render position and the position sent to the server. That trades a collision
artefact for a desync.

Set `hitbox.centre_pivot = false` to hand the pivot back to Sable; `Clearance` reads the same flag
and will then test the eye-pivoted box, so the two stay consistent either way.

### Clearance

Before a new hitbox orientation is published, twelve sample points of the rotated box are tested for
free space. If the full step does not fit, half and quarter steps are tried.

If none fit, **the previous orientation is kept**. The old build fell back to upright, which meant a
tight spot produced tilt, reject, upright, tilt, reject - the buzz. Holding the last good pose
instead means a blocked rotation is simply a pause, and the overlay says `BLOCKED` while it lasts.

---

## Forces, friction and sliding

One solve, in this order, every tick:

1. **Load** = ride + gravity + drag. Everything you can feel, in one vector.
2. **Stick cancels** its share of the tangential part, and its share of any outward normal part.
3. **Friction** holds what it can: `grip.strength * press * footing`, with a bonus while you are
   pushing into the surface. Under budget nothing moves; over budget only the excess gets through.
4. **The excess is opposed by a viscous term**, so a slide accelerates, settles at a terminal speed
   and stops accelerating.
5. **Air's share bypasses friction** entirely, paired with damping sized so the drift settles at
   `air_resistance.slide_max_speed`.
6. Gravity is subtracted at the end, because Minecraft already applied it.

Step 6 gives the correctness test the whole file is built around: **on a stationary deck the applied
vector is exactly zero.** No ride, no stick, nothing cancelled, load is gravity, gravity minus
gravity is nothing. The mod cannot perturb ordinary play - not because the thresholds are high, but
because there is nothing for it to add.

### Why sliding was unpleasant before

Three separate reasons, all removed by folding the three solvers into one:

- **The slide solver never saw gravity.** It considered only the fictitious forces; tangential
  gravity was handled by a different subsystem. So the thing pulling you down the wall and the thing
  deciding whether you slid were not the same calculation.
- **The excess was clipped, not damped.** `(limit - already) / limit` scales the acceleration but
  leaves it positive forever, so a slide kept gaining speed for as long as the load was over budget.
  It never converged, so it never read as sliding - only as losing control.
- **`grip` was a gate.** At 3 it closed completely and sliding disappeared rather than reduced.
  Grip is now a threshold that the load is measured against, with the remainder passed on, so higher
  grip makes slides slower and never absent.

### Adhesion at the top of a loop

When the load is pulling you off the surface, `stick` supplies the press for the friction budget
instead. Without that clause, hanging upside down would come with zero grip - attached, but sliding
sideways off the deck.

---

## Camera

Target is the committed plane, scaled by `stick`, plus a small capped lean towards felt-down.
Because the target is a committed plane it is a step function: it does not wobble when the forces
wobble, so there is nothing for the smoothing to chase.

`TiltSpring` is a critically damped second-order spring on the rotation vector, substepped so it
behaves the same at 30 and 300 fps, and rate-limited so a contraption that snaps 180 degrees in a
tick cannot do the same to your head.

The old camera was an exponential low-pass with a dead band in front. Both halves are why it felt
wrong: a low-pass is fastest when the target moves and slowest as it arrives, the opposite of how a
head moves; and the dead band held the camera still until the error crossed a threshold and then
released it in one lump. A spring has momentum - it leans out ahead and arrives with its velocity
already decaying. There is no dead band at all, because a critically damped spring is already still
when its error is small.

`camera.lead_seconds` aims a fixed time ahead of where the target is going, which cancels most of
the residual lag without buying the overshoot that lowering the damping would.
`camera.pitch_response` takes a share off the vertical component only - applied to the spring's
error rather than its output, so it stays critically damped instead of permanently fighting a
scaled-down result.

**ACS is required for the camera** and for nothing else. Without it the physics, the rotated hitbox
and the overlay all work; the view simply stays level. Winning an ACS frame means owning the
crosshair, the reach rays, projectile direction and what the server is told, so the source declines
every frame it has nothing to say about - and keeps claiming while the spring unwinds, because
letting go mid-lean would hand ACS a camera halfway over.

---

## Debug overlay

`/sable_cf debug_overlay` - arrows in the world, numbers in the corner.

| Arrow | Colour |
| --- | --- |
| air velocity | white |
| centrifugal | orange |
| drag | blue |
| apparent (felt-down) | violet |
| plane normal | green |

The numbers to read, in order:

- **`stick`** - how much of a wall-walker you are. If this is 0, nothing this mod does is visible,
  so whatever is moving you is not this mod.
- **`press` / `hold` / `load`** - friction can hold `hold`, something is pushing with `load`. The
  moment load passes hold you start moving, and you can watch it happen.
- **`plane`** - which face is committed, and which is challenging it and for how many ticks. A
  challenger that never reaches the dwell count is hysteresis doing its job.
- **`body` / `hitbox`** - the two angles should track. A persistent gap plus `BLOCKED` means the
  rotation did not fit and is waiting.

Arrow smoothing runs on wall-clock time, so it looks the same at any frame rate.

---

## Known limits

- **Client-authoritative.** Forces are applied on the client that owns the player. A server that
  runs strict movement checks will disagree with a player being held to a wall.
- **Shape detail.** A probe hit on a slab or a stair is treated as an axis-aligned face. Sable
  computes the true contact normal internally; see `docs/UPSTREAM.md`.
- **One sub-level at a time.** The frame tracks whichever sub-level Sable says you are on.
- **Angular velocity is differentiated, not read.** Sable has the networked value and keeps it
  private, so a small filter delay remains; `centrifugal_force.lead_ticks` compensates for it.
- **The pivot fix is a mixin.** If Sable refactors `transformEntityBoundsCenter` the injection
  quietly does not apply (`defaultRequire: 0`) and the old pivot returns; turn `hitbox.max_deg` down
  if that happens.

---

## Upstream

See `docs/UPSTREAM.md`. Six requests, each with the code path that forced it - the two that would
remove code from this mod entirely are an orientation provider registry in Sable, and making the
collision-box pivot configurable.
