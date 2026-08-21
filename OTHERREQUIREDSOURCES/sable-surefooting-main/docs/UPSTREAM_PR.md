# Draft PR: Keep tracking sub-levels through airborne jump/fall arcs

Target: `ryanhcode/sable` (branch drafted locally as `fix/jump-airborne-tracking`).
Status: **draft — do not open until the standalone mod validates the approach in-game.**

---

## Title

Keep tracking sub-levels through airborne jump/fall arcs

## Body

### The bug

Jumping while standing on a moving sub-level does not apply the sub-level's motion through the jump arc.

Repro (observed on 2.0.3, singleplayer):
1. Assemble a platform on a swivel bearing and spin it at 16 RPM.
2. Stand centered above the bearing axis and jump repeatedly while otherwise stationary: each landing is offset by ~0.001–0.005 blocks, and the error accumulates until you slide off.
3. Stand at radius on a rotating arm and jump straight up: you get no rotational carry at all — the arm rotates out from under you and you fall off beside it (or clip past it).

### Root cause

While an entity is tracking a sub-level, `SubLevelEntityCollision.collide()` warps it by the sub-level's pose delta each substep — this correctly carries both translation and rotation. But tracking is dropped (`stopTrackingAtEnd`) as soon as no sub-level blocks are inside the entity's local context bounds (minus 1 block Y), which happens near the apex of a normal jump.

From that point the entity is carried only by `sable$inheritedVelocity` — a linear velocity snapshot decaying at 0.99/tick. A linear vector cannot represent a rotating reference frame: near the rotation axis it is ~zero (hence the slow accumulating drift), and at radius it is a decaying straight-line push that immediately diverges from the platform's motion.

### The fix

Two parts:

1. **Keep the tracking warp active while airborne.** `stopTrackingAtEnd` is now only set when the entity is on ground, mounted, in water/lava, elytra- or creative-flying, or more than 4 blocks outside the sub-level's global bounding box. Tracking still ends exactly as before on vanilla ground contact (the y-collision clear in `EntityMixin#sable$collideRedirect`), when landing on another sub-level, or when the sub-level is removed. A jumping entity therefore stays in the sub-level's reference frame for the whole arc and lands on the spot it took off from. Entities that walk off an edge are carried only until they leave the release margin, so deliberately bailing off a contraption behaves as before after a few blocks of fall.

2. **Rotate the airborne entity's velocity with the frame.** The warp carries the entity's *position* with the rotating frame, but its jump velocity stays fixed in world space, so a jump aimed across a spinning platform lags ever further behind the rotation. While tracked and airborne, the entity's delta movement is rotated by the sub-level's per-tick orientation delta (`logicalPose().orientation() × lastPose().orientation()⁻¹`), giving platform-frame ballistics: a jump aimed at a spot on the platform lands on that spot, exactly as if the platform were stationary.

### Testing

Validated in-game (singleplayer, NeoForge 1.21.1, Sable 2.0.3, Create: Aeronautics 1.3.0) via a companion mod implementing the same logic through the duck interfaces, with per-tick sub-level-local telemetry:

- Swivel bearing platform at ~4.95 m/s tangential speed at the standing position (measured on a Create speedometer): jumping in place holds the platform-local position constant to 4 decimal places through the entire arc; landing offset 0.0000 (was 0.001–0.005/jump accumulating at 16 RPM, and a violent tangential fling at higher speeds).
- Sprint-jumping across the rotating platform: platform-local trajectory is a straight line at constant per-tick velocity, landing at the aimed spot ~4 blocks away — identical to a sprint-jump on a stationary platform (was: falls behind the rotation and misses the far side).
- Jumping off the edge of a moving contraption: releases within the bounds margin, no rubber-banding.
- Ground walking/strafing on the spinning platform: unchanged.

One implementation note learned the hard way: airborne tracking retention must release on a real landing (`onGround`), not on `verticalCollision` alone — at high rotation rates the substep collision can report a vertical clip mid-arc without an actual landing, and releasing there hands the (large) tangential inherited velocity over as a straight-line fling.
