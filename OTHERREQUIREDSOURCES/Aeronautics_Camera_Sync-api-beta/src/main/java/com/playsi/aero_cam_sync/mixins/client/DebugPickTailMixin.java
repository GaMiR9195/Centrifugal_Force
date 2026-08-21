package com.playsi.aero_cam_sync.mixins.client;

import com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess;
import com.playsi.aero_cam_sync.AeroCamSync;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.debug.PickDiagnostics;
import com.playsi.aero_cam_sync.client.aim.PickScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ВРЕМЕННАЯ ДИАГНОСТИКА (2026-08-03). Пишет результат КАЖДОГО вызова {@code pick(F)}, а не
 * только того, который обёрнут {@link PickScopeMixin}.
 *
 * <p>Проверяемая версия: пик за кадр вызывается больше одного раза, и последний вызов идёт
 * мимо нашего окна — тогда {@code mc.hitResult} к моменту клика ванильный, хотя наш пик
 * отработал правильно. Инжект на RETURN попадает внутрь вызова, поэтому
 * {@code PickScope.isActive()} здесь честно показывает, был ли этот конкретный вызов
 * в окне.</p>
 */
@Mixin(GameRenderer.class)
public abstract class DebugPickTailMixin {

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void aero$recordPickTail(float partialTick, CallbackInfo ci) {
        PickDiagnostics.picksThisFrame++;
        PickDiagnostics.lastPickTail = (PickScope.isActive() ? "scoped:" : "UNSCOPED:")
                + aero$describeTail(Minecraft.getInstance().hitResult);

        // Второй вызов пика за кадр так и не опознан, а он уже дважды оказывался причиной
        // (грань установки, поза для чужих лучей). Печатаем каждого уникального вызывающего.
        if (ClientTiltAccess.isDebugMessages()) {
            PickDiagnostics.logOnce("вызов pick", PickDiagnostics.caller());
        }
    }

    /**
     * Имя с префиксом обязательно: приватные методы миксинов мержатся в целевой класс, и пока
     * этот метод звался просто {@code describe}, он сталкивался с одноимённым из
     * {@code PickScopeMixin} — {@code Method overwrite conflict for describe} в логе и потеря
     * префикса {@code BLOCK@} в выводе. В продакшене конфликта нет и без этого (миксин туда не
     * попадает — см. {@code DebugMixinPlugin}), но в дев-ране он был виден каждый запуск.
     */
    private static String aero$describeTail(HitResult hit) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) return "MISS";
        if (hit instanceof BlockHitResult block) {
            return block.getBlockPos().toShortString() + " " + block.getDirection();
        }
        return hit.getType().toString();
    }
}
