package com.playsi.aero_cam_sync.mixins.client;

import com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.debug.PickDiagnostics;
import com.playsi.aero_cam_sync.client.aim.PickScope;
import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ВРЕМЕННАЯ ДИАГНОСТИКА (2026-08-03). Кто спрашивает глаз игрока ВНЕ окна пика.
 *
 * <p>Проверяемая версия: {@code @WrapMethod} переносит тело {@code pick(F)V} в синтетический
 * метод, поэтому чужой {@code @Inject(TAIL)}, применённый после нас, садится на обёртку и
 * выполняется уже после {@code PickScope.close()}. Такой мод получает НЕнаклонённый глаз и
 * промахивается (симптом Dashpanels). Если версия верна — здесь всплывёт его класс.</p>
 *
 * <p>Рендерные пути Sable (световой пробник, смещение рендера сущностей) зовут воронку каждый
 * кадр и тоже попадут в лог — поэтому печатаем вызывающего, чтобы их отличать, и не чаще
 * раза в секунду.</p>
 */
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class DebugFunnelMixin {

    @Inject(method = "getEyePositionInterpolated", at = @At("HEAD"))
    private void aero$logOutsideScope(Entity entity, float partialTicks,
                                      CallbackInfoReturnable<Vec3> cir) {
        if (!ClientTiltAccess.isDebugMessages()) return;

        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || entity != mc.player) return;

        // Печатаем ВСЕХ потребителей воронки, а не только тех, кто вне окна: нужен полный
        // список, чтобы решить, можно ли перевернуть гейт (тилтить по умолчанию, исключая
        // рендерные пути). Каждый уникальный вызывающий — один раз.
        PickDiagnostics.logOnce(PickScope.isActive() ? "воронка В ОКНЕ" : "воронка ВНЕ окна",
                PickDiagnostics.caller());
    }
}
