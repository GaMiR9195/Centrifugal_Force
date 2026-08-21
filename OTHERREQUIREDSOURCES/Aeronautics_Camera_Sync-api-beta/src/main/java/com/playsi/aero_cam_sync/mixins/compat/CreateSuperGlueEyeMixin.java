package com.playsi.aero_cam_sync.mixins.compat;

import com.playsi.aero_cam_sync.TiltAccess;
import com.simibubi.create.content.contraptions.glue.SuperGlueSelectionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Супер-клей (Create): выбор уже поставленного клея — из наклонённой камеры.
 *
 * <p><b>Симптом.</b> Блочная часть работает (первая и вторая точки берутся из
 * {@code mc.hitResult}), а вот навести на существующий клей и снять его левым кликом на
 * наклонённой палубе получается через раз: подсвечивается не тот бокс или не подсвечивается
 * ни один.</p>
 *
 * <p><b>Причина.</b> {@code SuperGlueSelectionHandler#tick} ищет клей своим лучом:</p>
 * <pre>
 *   Vec3 traceOrigin = player.getEyePosition();                       // ванильный глаз
 *   Vec3 traceTarget = RaycastHelper.getTraceTarget(player, r, org);  // наклоняет CreateRaycastTiltMixin
 *   glueEntity.getBoundingBox().clip(traceOrigin, traceTarget);       // AABB, а не Level#clip
 * </pre>
 * <p>Тот же гибрид, что у honey glue (§1.6), и по той же причине мимо сети: пересечение
 * считается {@code AABB#clip}, до {@code Level#clip} дело не доходит (§1.8). Побочно на
 * сдвинутом начале чинится и метрика — {@code distanceToSqr(traceOrigin)} выбирает
 * ближайший бокс от той же точки, откуда пущен луч.</p>
 *
 * <p><b>Саблевел тут уже учтён Sable</b> ({@code compatibility/create/super_glue/
 * SuperGlueSelectionHandlerMixin} проецирует бокс клея в мир по {@code logicalPose}), поэтому
 * от нас нужно ровно одно — начало луча.</p>
 *
 * <p><b>Чего этот миксин НЕ чинит.</b> Автоматическую склейку при установке блока с клеем в
 * левой руке ({@code SuperGlueHandler#glueInOffHandAppliesOnBlockPlace}): она серверная и
 * клипает не уровень, а обёртку {@code catnip RayTraceLevel}, которая {@code Level} не является.
 * Из-за этого мимо неё проходит и наша сеть (у неё {@code blockGetter instanceof Level}), и
 * {@code @Overwrite} Sable на {@code BlockGetter#clip} (для не-{@code Level} он уходит в
 * {@code originalClip}) — то есть на палубе этот путь не работает и без нас.</p>
 */
@Mixin(value = SuperGlueSelectionHandler.class, remap = false)
public abstract class CreateSuperGlueEyeMixin {

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition()Lnet/minecraft/world/phys/Vec3;",
                    remap = true),
            require = 0
    )
    private Vec3 aero$tiltedEye(LocalPlayer player) {
        return TiltAccess.aimEyePosition(player);
    }
}
