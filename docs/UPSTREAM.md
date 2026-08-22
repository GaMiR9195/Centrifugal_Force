# Upstream requests

This mod would rather call an API than mix in. Two mixins remain, and each maps to one small change
upstream. Written to be pasteable into an issue.

---

## Sable

### 1. `hasCustomEntityOrientation` and `getCustomEntityOrientation` contradict each other

`EntitySubLevelUtil.getCustomEntityOrientation` is the natural hook for an entity that should be
oriented by something other than the sub-level's own pose, and `mixin/entity/entities_turn_with_sub_levels/GameRendererMixin`
reads it. But the guard around that call is:

```java
if (standingSubLevel != null && player.getVehicle() == null && !standingSubLevel.isRemoved()
        && !EntitySubLevelUtil.hasCustomEntityOrientation(player)) {
    final Quaterniondc customOrientation = EntitySubLevelUtil.getCustomEntityOrientation(player, 1.0f);
    ...
}
```

So `hasCustomEntityOrientation` returning `true` disables the entire block, including the call to
`getCustomEntityOrientation` - the two methods cannot both be answered honestly. Anyone supplying a
custom orientation has to override the getter while leaving the predicate returning `false`, which
reads like a bug even though it is the only thing that works.

**Ask:** either let a provider return a non-null orientation with the predicate returning `true`, or
drop the predicate from the guard. Both currently return constants (`null` / `false`), so this is a
free change.

**Better ask:** a small registration surface, so no mixin is needed at all:

```java
EntitySubLevelUtil.registerOrientationProvider((entity, partialTicks) -> quaternionOrNull);
```

### 2. The contact manifold is `@ApiStatus.Internal`

`EntityMovementExtension#sable$getCollisionInfo()` is exactly what a mod needs to know *which*
surface an entity is resting against, and it is marked internal. Without it, the surface normal has
to be re-derived by testing the six deck axes against felt-down and blending them (`SurfaceEstimator`
here) - which works, and even has the pleasant side effect of a smooth floor-to-wall transition, but
it is a reconstruction of information Sable already has exactly.

**Ask:** a read-only accessor for the resting surface normal, or a supported view of the manifold.

### 3. Eye position is not offset along the body's up axis

With a rotated hitbox, the eye should sit along the body's own up axis rather than at vanilla eye
height on the world's Y. Vanilla computes eye position in several places, and patching all of them
from a downstream mod means either a broad mixin or a visible discrepancy. This mod currently accepts
the discrepancy: lying against a drum wall, the camera is slightly inside the body.

**Ask:** route eye position through one overridable point, or offer
`EntitySubLevelUtil.getCustomEyeOffset(entity, partialTicks)` alongside the orientation hook.

### 4. Collision testing against an oriented box

The deepest version of the same request. `Entity#makeBoundingBox` is mixed in here to re-fit a
rotated OBB to an AABB, which necessarily inflates it - at 45 degrees a 0.6 x 1.8 player needs about
1.7 blocks of width, enough to wedge in a one-block corridor. Sable already does oriented collision
work against sub-level geometry internally.

**Ask:** let an entity declare a body orientation that Sable's collision path uses directly, instead
of every downstream mod approximating it with an inflated AABB.

---

## Aeronautics Camera Sync

### 5. Release `addTiltSource`

`AcsHandle#addTiltSource(int priority, TiltSource)` exists on `api-beta` but not in `1.3.7`. It is
the correct extension point and it works well - priority ordering, `appliesTo` gating, `tiltScale()`
applied for free - so the only problem is that using it means asking people to build a branch.

**Ask:** ship it in a release. Nothing else is needed.

Two notes from using it, both worth documenting rather than changing:

- `AcsHandle#state()` must not be called from inside a `TiltSource`, since `state()` polls the
  sources. It is a straightforward infinite recursion and the fix is to read everything from
  `TiltContext` - which already carries what is needed. Worth a line in `docs/API.md`.
- `TiltContext#deltaTicks()` being realtime rather than tick-quantised is what makes a framerate
  independent spring possible. Please keep it.

---

## Minecraft / NeoForge

### 6. `RenderType.create` is `protected static`

Not a request, a note on why this mod's debug arrows do not draw through terrain. Building a render
type with `NO_DEPTH_TEST` needs `RenderType.create`, which requires an access transformer, and
setting `RenderSystem.disableDepthTest()` by hand does not work because a render type re-applies its
own depth shard in `setupRenderState()` when the batch is flushed. The arrows are biased towards the
camera instead, which puts them in front of the player model - the actual complaint - and world
geometry still occludes them. If the overlay ever becomes more than a debug aid, add the AT.
