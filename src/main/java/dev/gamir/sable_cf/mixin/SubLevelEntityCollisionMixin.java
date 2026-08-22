package dev.gamir.sable_cf.mixin;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rotates the collision box about its own centre instead of about the player's eye.
 *
 * <h2>The bug this removes</h2>
 *
 * <p>Sable pivots the oriented box at eye height:</p>
 *
 * <pre>offset = (0, eyeHeight - ysize/2, 0)   // 0.72 for a standing player
 *center.add(offset).sub(R.transform(offset));</pre>
 *
 * <p>Which is fine for the small tilts it was written for and catastrophic for large ones. The box
 * centre sweeps {@code 2 * 0.72 * sin(A/2)}: 0.13 blocks at 10 degrees, 1.02 at 90, 1.44 at 180. It
 * is a cartwheel, not a lean - the body swings bodily sideways rather than turning in place, and it
 * does so <i>into</i> whatever it is leaning towards.</p>
 *
 * <p>Sable then finds the penetration it just created and resolves it, and its near-vertical branch
 * redirects the whole minimum translation vector along the body's up axis at full length:</p>
 *
 * <pre>if (dot &gt; 0.8) { entityUp.mul(maxMTV.dot(entityUp), maxMTV).normalize(preLength); }</pre>
 *
 * <p>A metre of penetration therefore comes back as a metre-long shove pointing up and out. Eight
 * substeps, up to four resolutions each. That is precisely the reported symptom - "as soon as the
 * player walks in it drags them sideways or starts lifting them up and shaking the visual and the
 * hitbox" - and it is created by the pivot before any force in this mod is consulted, which is why
 * no amount of tuning ever made it go away.</p>
 *
 * <h2>Why cancelling the method is the correct fix, not a hack</h2>
 *
 * <p>Checked against every use of the pivot in Sable's collision code:</p>
 *
 * <ul>
 *   <li>It is applied in exactly one place, this method, and only to the box centre.</li>
 *   <li>Inside the substep loop the pair {@code fma(+eyeHeight, entityUp_old)} /
 *       {@code fma(-eyeHeight, entityUp_new)} cancels exactly, because this mod returns the same
 *       orientation for every {@code partialTicks} within a tick. Nothing there depends on where
 *       the pivot was.</li>
 *   <li>{@code getFeetPos} is only ever consumed as a difference under the same rotation, so the
 *       pivot cancels there too.</li>
 * </ul>
 *
 * <p>So removing it leaves a box that rotates about its own centre, which is what a body turning in
 * place actually does. The swept displacement drops from 1.02 blocks at 90 degrees to
 * {@code (1.8 - 0.6) / 2 = 0.6}, and - the part that matters for the "solnyshko" - over a full 360
 * it drops to <b>zero</b>: the box ends a loop exactly where it started, so there is no accumulated
 * offset to fight and the hitbox can be allowed to follow the ride all the way round.</p>
 *
 * <h2>Compensating the player's position instead was considered and rejected</h2>
 *
 * <p>The alternative is to leave the pivot alone and move the player so the feet stay put, which
 * needs {@code (I - R) * (0, eyeHeight, 0)} - between 1.02 and 2.29 blocks of real displacement.
 * That is a genuine teleport: it moves the vanilla AABB, the render position and the position sent
 * to the server, all of which still believe the player is an upright box at that point. It trades a
 * collision artefact for a desync, which is a worse bug in a more confusing place.</p>
 *
 * <h2>Failing safe</h2>
 *
 * <p>{@code defaultRequire: 0} in the mixin config, so if Sable refactors this method the injection
 * quietly does not apply and the mod keeps working with the old pivot - {@link
 * dev.gamir.sable_cf.physics.Clearance} reads the same config flag and will test the eye-pivoted
 * box instead, and {@code hitbox.max_deg} can be turned down to stay inside the safe range. Nothing
 * here is load-bearing for the mod's correctness, only for how far it is comfortable to lean.</p>
 *
 * <p>Upstream ask #2 in {@code docs/UPSTREAM.md} is for this to be a Sable option, at which point
 * this file deletes cleanly.</p>
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionMixin {

    @Inject(method = "transformEntityBoundsCenter", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sable_cf$centrePivot(
            final LevelReusedVectors sink,
            final Quaterniondc customOrientation,
            final Entity entity,
            final Vector3d center,
            final CallbackInfo callback) {

        if (customOrientation == null || entity == null || !CfConfig.SPEC.isLoaded()) {
            return;
        }

        if (!CfConfig.HITBOX_ENABLED.get() || !CfConfig.HITBOX_CENTRE_PIVOT.get()) {
            return;
        }

        if (!(entity instanceof BodyFrameHolder holder)) {
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();

        // Only for entities whose orientation WE supplied. Another mod's tilt, or a future Sable
        // feature, keeps Sable's own pivot - we are correcting our own interaction, not
        // redefining the engine for everyone.
        if (frame == null || frame.collisionOrientation() == null) {
            return;
        }

        callback.cancel();
    }
}
