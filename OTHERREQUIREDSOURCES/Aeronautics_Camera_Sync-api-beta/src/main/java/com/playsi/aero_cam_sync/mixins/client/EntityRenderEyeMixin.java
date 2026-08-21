package com.playsi.aero_cam_sync.mixins.client;

import com.playsi.aero_cam_sync.client.aim.RenderEyeScope;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Отмечает два рендерных метода, внутри которых воронка Sable должна отдавать настоящий глаз.
 *
 * <p>Оба метода ванильные, но интересны они тем, что Sable вставляет в них свои хуки и зовёт
 * оттуда {@code getEyePositionInterpolated}: {@code getPackedLightCoords} — световой пробник,
 * {@code shouldRender} — отбор по видимости. Оборачиваем ванильный метод целиком, поэтому
 * хук Sable гарантированно оказывается внутри окна независимо от порядка применения миксинов.</p>
 *
 * <p>Обоснование списка и цена — в {@link RenderEyeScope}.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRenderEyeMixin {

    @Inject(method = "getPackedLightCoords", at = @At("HEAD"))
    private void aero$lightProbeEnter(CallbackInfoReturnable<Integer> cir) {
        RenderEyeScope.enter();
    }

    @Inject(method = "getPackedLightCoords", at = @At("RETURN"))
    private void aero$lightProbeExit(CallbackInfoReturnable<Integer> cir) {
        RenderEyeScope.exit();
    }

    @Inject(method = "shouldRender", at = @At("HEAD"))
    private void aero$visibilityEnter(CallbackInfoReturnable<Boolean> cir) {
        RenderEyeScope.enter();
    }

    @Inject(method = "shouldRender", at = @At("RETURN"))
    private void aero$visibilityExit(CallbackInfoReturnable<Boolean> cir) {
        RenderEyeScope.exit();
    }
}
