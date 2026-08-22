# Upstream requests

Seven requests, each with the code path that forced it. Written to be pasted into an issue.

One request from the earlier version of this document has been **withdrawn** - see the end.

---

## 1. Sable: implement `getCustomEntityOrientation`, and fix the predicate that disables it

**Where:** `api/entity/EntitySubLevelUtil` - `getCustomEntityOrientation` returns `null`,
`hasCustomEntityOrientation` returns `false`.

The shape of the API is already right; there is simply nothing behind it. Everything an external mod
needs in order to rotate an entity's collision box is a way to answer "what is this entity's
orientation" - Sable's own collision code then builds the oriented box and runs SAT, which it already
does well.

**Ask:** a small provider registry, roughly
`registerOrientationProvider(Predicate<Entity>, Function<Entity, Quaterniondc>)`, consulted by both
methods. Five lines plus a list.

**The contradiction, which matters more than the stub:** in `GameRendererMixin` the condition
includes `!hasCustomEntityOrientation(player)`. A provider that answers `true` there switches off the
entire block *including the call to the getter*. So the hook, once implemented naively, disables
itself. Whatever the registry ends up looking like, these two call sites need to agree on the
polarity.

This mod currently mixes into `getCustomEntityOrientation` at `HEAD` and deliberately leaves
`hasCustomEntityOrientation` returning `false` for exactly that reason. That is a workaround, and it
is the only reason a mixin is needed here at all.

---

## 2. Sable: make the collision-box rotation pivot configurable

**Where:** `sublevel/entity_collision/SubLevelEntityCollision.transformEntityBoundsCenter`.

```java
final Vector3d offset = sink.anchorRelativePosition.set(
        0.0, entity.getEyeHeight() - entity.getBoundingBox().getYsize() / 2.0, 0.0);
center.add(offset).sub(customOrientation.transform(offset));
```

The oriented box is rotated about a point at eye height rather than about its own centre. For the
small tilts this was written for that is a reasonable choice - the head stays put and the feet swing
a little. For large angles it is not: with `offset = 0.72` for a standing player, the box centre
sweeps `2 * 0.72 * sin(A/2)`, which is 0.13 blocks at 10 degrees, **1.02 at 90** and 1.44 at 180.

The box is therefore translated bodily into whatever surface it is leaning towards, and the
penetration that creates is real. Resolution then hits this branch:

```java
if (dot > 0.8) { entityUp.mul(maxMTV.dot(entityUp), maxMTV).normalize(preLength); }
```

which redirects the entire MTV along the body up axis at full length - so a metre of self-inflicted
penetration returns as a metre-long shove up and outward, up to four times per substep and eight
substeps per tick for a local player. The visible result is an entity that lifts and vibrates as
soon as it tilts more than a little, and it is produced entirely inside Sable, before any consumer
force is applied.

There is a second consequence that matters for rides: over a full 360-degree rotation the eye pivot
integrates to a **non-zero net displacement**, so an entity carried once around a loop does not
return to where it started relative to the deck. About its own centre, it does - exactly.

**Ask:** a pivot choice on the orientation provider, or simply a boolean, defaulting to today's
behaviour:

```java
enum OrientationPivot { EYE, BOUNDS_CENTRE }
```

A consumer that rotates entities to 90 degrees or beyond needs `BOUNDS_CENTRE`; one that leans them
slightly is better off with `EYE`. Sable already knows both points.

This mod currently ships a mixin that cancels the method outright when the orientation is its own,
which is sound only because the pivot has exactly one use inside collision: the
`fma(+eyeHeight, up_old)` / `fma(-eyeHeight, up_new)` pair in the substep loop cancels for a
consumer that returns one orientation per tick, and `getFeetPos` is only ever consumed as a
difference under the same rotation. It is nonetheless a mixin over an internal method, and it is
the only remaining one that could break on a Sable point release.

---

## 3. Sable: public read access to the contact manifold

**Where:** `sable$getCollisionInfo()` is `@ApiStatus.Internal`.

**Priority: low - accuracy only.** This was a bigger ask in the previous revision, when the surface
normal was inferred by choosing whichever of the six sub-level axes was most opposed to felt-down.
That inference is gone; the mod now probes real block shapes along each local axis, which is correct
for flat faces and for multi-contact blends.

What is still approximate is **shape detail**: a slab, a stair or a fence gives a probe hit whose
normal is taken as the axis normal. Sable already computes the true contact normal internally during
resolution. Exposing a read-only view of it would remove the last approximation, and would let
consumers stop duplicating shape queries that Sable performs anyway.

**Ask:** a read-only accessor, or a small immutable record of the last resolved contacts (normal,
depth, and which sub-level).

---

## 4. Sable: public angular velocity of a sub-level on the client

The field exists and is private. External code is left differentiating successive poses, which is
noisy exactly where it matters most - the moment a ride changes rate - and forces a low-pass filter
that costs responsiveness to buy stability.

**Ask:** `Vector3dc angularVelocity()` on `SubLevel`, available client-side. If the value is already
maintained for the physics step, publishing it costs nothing and is strictly better than every
consumer's finite difference.

---

## 5. Sable: offset the eye position along the entity's body up-vector

With a rotated collision box, the eye is still placed at `position + eyeHeight * worldUp`. Lying
against the wall of a spinning drum, that puts the camera slightly inside the body.

**Ask:** when a custom orientation is present, place the eye at
`position + orientation * (eyeHeight * up)`. Sable already pivots the oriented box about eye height,
so the quantity is on hand at that point in the code.

This cannot be fixed from outside: `getEyePosition` feeds picking, particles, sounds and the server's
view of where the player is looking, and overriding it from another mod would fight ACS's ray
correction rather than cooperate with it.

---

## 6. Sure Footing: a release hook

Sure Footing keeps a player in the sub-level frame across a jump arc, which is the correct behaviour
and the reason this mod does not touch that part of the problem at all.

What is missing is a way to say "stop carrying this player now": when centrifugal load plus air drag
exceed friction, the player should leave the frame *at that instant*, with Sable's inherited
velocity. Today an external mod can only apply forces and hope the carry state resolves in the same
tick.

**Ask:** either `releaseCarry(Player)`, or a veto hook on `canCarry` so another mod can decline the
carry for a tick without racing it.

---

## 7. ACS: release `addTiltSource`

`AcsHandle#addTiltSource(int, TiltSource)` exists on the `api-beta` line. Released 1.3.7 exposes only
`addListener` and `addPolicy`, and neither can set tilt - a listener observes it and a policy affects
aiming. So any mod that wants to *contribute* a camera orientation currently cannot compile against a
published artifact, which is a hard blocker rather than an inconvenience.

**Ask:** ship `addTiltSource` in a release.

Two notes from building on it, offered as documentation material:

- **`AcsHandle#state()` must not be called from inside a `TiltSource`.** It re-enters the source and
  produces a `StackOverflowError`. Worth a line in the javadoc, or an explicit guard.
- The priority contract ("highest wins", not "blend") is the right choice and worth stating
  explicitly, because the natural assumption from the name is that sources compose.

---

## Withdrawn: "collision testing against an oriented box"

The previous revision of this document asked Sable to test collision against an oriented box instead
of an axis-aligned one. **Sable already does this**, and the ask was based on a misreading.

`SubLevelEntityCollision` builds an `OrientedBoundingBox3d` from the entity's unrotated
`getXsize/getYsize/getZsize` together with the supplied quaternion and runs SAT against sub-level
blocks. It also expands its consideration bounds by `getEyeHeight()` and pivots about eye height
rather than the feet.

The practical consequence is worth recording, because it caused a real bug here: **inflating the
entity's axis-aligned box to enclose the rotated box accomplishes nothing and actively harms.** Sable
does not consult that box for sub-level collision, while vanilla collision does - so the inflated box
only ever wedged the player against ordinary main-level blocks in tight spaces. `collide()` also
early-returns for `ServerPlayer`, so the two sides did not even agree about the inflated shape.

Supplying the orientation and nothing else is the correct integration.
