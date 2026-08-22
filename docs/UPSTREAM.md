# Upstream requests

Five requests, each with the code path that forced it. Written to be pasted into an issue.

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

## 2. Sable: public read access to the contact manifold

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

## 3. Sable: public angular velocity of a sub-level on the client

The field exists and is private. External code is left differentiating successive poses, which is
noisy exactly where it matters most - the moment a ride changes rate - and forces a low-pass filter
that costs responsiveness to buy stability.

**Ask:** `Vector3dc angularVelocity()` on `SubLevel`, available client-side. If the value is already
maintained for the physics step, publishing it costs nothing and is strictly better than every
consumer's finite difference.

---

## 4. Sable: offset the eye position along the entity's body up-vector

With a rotated collision box, the eye is still placed at `position + eyeHeight * worldUp`. Lying
against the wall of a spinning drum, that puts the camera slightly inside the body.

**Ask:** when a custom orientation is present, place the eye at
`position + orientation * (eyeHeight * up)`. Sable already pivots the oriented box about eye height,
so the quantity is on hand at that point in the code.

This cannot be fixed from outside: `getEyePosition` feeds picking, particles, sounds and the server's
view of where the player is looking, and overriding it from another mod would fight ACS's ray
correction rather than cooperate with it.

---

## 5. Sure Footing: a release hook

Sure Footing keeps a player in the sub-level frame across a jump arc, which is the correct behaviour
and the reason this mod does not touch that part of the problem at all.

What is missing is a way to say "stop carrying this player now": when centrifugal load plus air drag
exceed friction, the player should leave the frame *at that instant*, with Sable's inherited
velocity. Today an external mod can only apply forces and hope the carry state resolves in the same
tick.

**Ask:** either `releaseCarry(Player)`, or a veto hook on `canCarry` so another mod can decline the
carry for a tick without racing it.

---

## 6. ACS: release `addTiltSource`

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
