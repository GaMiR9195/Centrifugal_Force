# Upstream API asks

Things `sable_cf` currently works around, in rough order of how much difference they would make.
Each one is small on the upstream side and removes a workaround - or, in the first case, unlocks a
feature that cannot be built from outside at all.

---

## 1. Sable - finish wiring `getCustomEntityOrientation`

**Where:** `dev.ryanhcode.sable.api.entity.EntitySubLevelUtil`

Both of these are stubs in 2.0.3:

```java
public static Quaterniondc getCustomEntityOrientation(Entity entity) { return null; }
public static boolean hasCustomEntityOrientation(Entity entity)      { return false; }
```

The shape is already exactly right - there is just nothing behind it. A provider registry would do
it:

```java
public static void registerOrientationProvider(Function<Entity, Quaterniondc> provider);
```

**Why it matters:** this is the only route to rotating a player's hitbox and model with the
sub-level. Without it, an upright AABB cannot fit through a doorway on a rolled deck, and "walking
around inside a rotating drum" stays an approximation. It is also the one thing on this list that no
amount of external code can substitute for. (Worth saying clearly: **ACS does not do this either.**
ACS rotates the camera and corrects rays; the hitbox, the model and the server's opinion all stay
vanilla. It is a common misreading of what camera sync provides.)

---

## 2. Sable - expose the contact normal

**Where:** `EntityMovementExtension#sable$getCollisionInfo()`, `@ApiStatus.Internal`

Sable already computes a real contact manifold against sub-level geometry. From outside, the best
available substitute is to take the sub-level's six local axes, pick whichever is most opposed to
felt gravity, and confirm contact from `onGround`/`horizontalCollision`/`verticalCollision`.

That is exact for flat block faces and completely wrong for slabs, stairs and anything sloped.
A read-only public accessor - even just the normal - would fix it:

```java
public static Vec3 getContactNormal(Entity entity); // null when not in contact
```

A raycast is not a workaround here: hits on sub-level blocks come back in the sub-level's own
coordinate space, millions of blocks from the player, so every result needs converting and
disambiguating first.

---

## 3. Sable - client-side angular velocity

**Where:** `ClientSubLevel.latestNetworkedAngularVelocity` (private), `RigidBodyHandle` (server only)

Sable knows the angular velocity on both sides but exposes it on neither, so this mod differences
`logicalPose().orientation()` against `lastPose().orientation()` every tick and converts to
axis-angle. That works - it is what Sable's own client `getVelocity()` does for the linear case -
but it needs a near-identity guard to avoid a divide-by-zero NaN, needs an angle wrap past pi to
avoid reading a fast flip as a backwards spin, and it cannot see angular *acceleration* except as a
noisy second difference.

Mirroring the existing point-velocity helper would cover it:

```java
Vector3dc getAngularVelocity(Level level, SubLevelAccess subLevel); // rad/s, world space
```

---

## 4. Sure Footing - a way to end a carry

**Where:** `JumpCarryHandler`

Sure Footing re-asserts the tracking sub-level every tick while you are airborne, and only lets go
on `onGround` or past `exit_distance_blocks`. When a spinner flings you off at speed, the right
behaviour is to leave the frame *immediately* - continuing to rotate the velocity vector with a drum
you are no longer touching sends you somewhere strange.

The only lever available from outside is to null Sable's tracking sub-level ourselves, which is a
write into state Sure Footing also writes, in the same tick phase, where the winner depends on
handler registration order. It is off by default here for that reason.

A one-liner solves it:

```java
public static void releaseCarry(Player player); // stop carrying, do not re-acquire this tick
```

A `canCarry` veto hook would work just as well.

---

## 5. ACS - release `addTiltSource` 

**Where:** `com.playsi.aero_cam_sync.api.AcsHandle`

`addTiltSource(int priority, TiltSource)` is on the `api-beta` branch but not in the published
1.3.7, which only has `addListener` and `addPolicy` - neither of which can supply a tilt. So the
camera half of this mod cannot compile against any released ACS, and users have to build a jar from
a branch.

The API itself is genuinely well designed for this: highest-priority source that claims the frame
owns the camera, the aim rays, the projectiles and the server's copy, all at once. It just needs to
ship.

Small notes from using it:

- Calling `AcsHandle#state()` from inside a `TiltSource` recurses forever. The javadoc says so;
  it might be worth a guard that throws something legible instead.
- `TiltContext#deltaTicks()` being realtime rather than game time is the right call and is what makes
  framerate-independent smoothing possible inside a source. Worth keeping prominent in the docs.
