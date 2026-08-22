package dev.gamir.sable_cf.mixin;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fills in Sable's own extension point for "this entity is not upright".
 *
 * <p>{@code EntitySubLevelUtil.getCustomEntityOrientation} currently returns null unconditionally -
 * it is a declared-but-unimplemented hook. It is not dead code, though, and it is worth being
 * precise about what supplying it actually buys, because it is more than cosmetics:</p>
 *
 * <ul>
 *   <li>{@code SubLevelEntityCollision} calls it once per collision substep and builds an
 *       <b>oriented</b> bounding box from it, then runs SAT against sub-level blocks. So this one
 *       return value is what turns the player's collision volume - genuinely rotated, not
 *       approximated by a larger axis-aligned box. That is why this mod no longer touches
 *       {@code Entity#makeBoundingBox} at all.</li>
 *   <li>Sable's {@code GameRendererMixin} uses it to compute the frame's rotation delta in the
 *       returned orientation's local space before applying yaw compensation, which is the
 *       correction a tilted body needs.</li>
 * </ul>
 *
 * <h2>Why only this method, and not hasCustomEntityOrientation</h2>
 *
 * <p>The obvious-looking companion method is a trap. Sable's own call site reads:</p>
 *
 * <pre>if (standingSubLevel != null &amp;&amp; ... &amp;&amp; !hasCustomEntityOrientation(player)) {
 *     ... getCustomEntityOrientation(player, 1.0f) ...
 * }</pre>
 *
 * <p>So {@code getCustomEntityOrientation} is only ever consulted when
 * {@code hasCustomEntityOrientation} returns <b>false</b>. Overriding the latter to true - the
 * intuitive thing to do when you are supplying an orientation - skips the whole block and disables
 * the yaw compensation entirely. Leaving it alone is what keeps the feature switched on. Worth
 * knowing before anyone "fixes" this file; it is upstream ask #1 in {@code docs/UPSTREAM.md}.</p>
 *
 * <h2>Why a mixin at all</h2>
 *
 * <p>There is no registry, event or service to supply this from outside, and the method is static.
 * When Sable implements the hook properly this entire class deletes cleanly - nothing else in the
 * mod depends on it existing.</p>
 */
@Mixin(value = EntitySubLevelUtil.class, remap = false)
public abstract class EntitySubLevelUtilMixin {

    @Inject(method = "getCustomEntityOrientation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sable_cf$provideOrientation(
            final Entity entity,
            final float partialTicks,
            final CallbackInfoReturnable<Quaterniondc> callback) {

        if (entity == null || !CfConfig.SPEC.isLoaded()) {
            return;
        }

        if (!(entity instanceof BodyFrameHolder holder)) {
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();

        if (frame == null) {
            return;
        }

        final Quaterniondc orientation = frame.collisionOrientation();

        // Upright is not "no orientation" by accident - returning identity here would send Sable
        // down its custom path to compute a delta that is provably identical to the one its default
        // path already computes, for every player on every deck, every frame.
        if (orientation == null) {
            return;
        }

        final Quaterniond copy = new Quaterniond(orientation);

        if (!Double.isFinite(copy.w) || copy.angle() < 1.0e-4) {
            return;
        }

        callback.setReturnValue(copy.normalize());
    }
}
