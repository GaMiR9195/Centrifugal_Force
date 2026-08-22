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
 * it is a declared-but-unimplemented hook. It is not dead code, though: Sable's
 * {@code GameRendererMixin} calls it every frame and, when it is non-null, computes the frame's
 * rotation delta in the returned orientation's <i>local</i> space before applying the yaw
 * compensation. That is exactly the correction a rotated body needs, so returning a real value here
 * makes Sable's existing yaw handling correct for a tilted player instead of fighting it.</p>
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

        if (entity == null || !CfConfig.SPEC.isLoaded() || !CfConfig.HITBOX_ENABLED.get()) {
            return;
        }

        if (!(entity instanceof BodyFrameHolder holder)) {
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();

        if (frame == null || !frame.isTilted()) {
            // Upright is not "no orientation" by accident - returning identity here would send
            // Sable down its custom path to compute a delta that is provably identical to the one
            // its default path already computes, for every player on every deck, every frame.
            return;
        }

        final Quaterniond orientation = new Quaterniond(frame.orientation());

        if (!Double.isFinite(orientation.w) || orientation.angle() < 1.0e-4) {
            return;
        }

        callback.setReturnValue(orientation.normalize());
    }
}
