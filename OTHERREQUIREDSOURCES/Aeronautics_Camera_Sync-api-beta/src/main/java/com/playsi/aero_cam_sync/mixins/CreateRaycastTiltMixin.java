package com.playsi.aero_cam_sync.mixins;

import com.playsi.aero_cam_sync.TiltAccess;
import com.simibubi.create.foundation.utility.RaycastHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Приводит лучи Create ({@link RaycastHelper}) в соответствие с наклонённой камерой.
 *
 * <p>Create считает свой луч от {@code getEyePosition()} и сырых {@code xRot/yRot}
 * (метод {@code getTraceTarget}), а не от нашей камеры. Поэтому при наклоне камеры
 * луч Create расходится с перекрестием: например, обработчик редстоун-линка
 * ({@code LinkHandler.onBlockActivated} → {@code rayTraceRange} → {@code LinkBehaviour.testHit})
 * не попадает в слот частоты, не отменяет событие — и вместо установки частоты ставится
 * блок «сверху». Срабатывает и для одного только поворота, и для одного только сдвига.</p>
 *
 * <p>Чиним обе составляющие, как и для остальных взаимодействий: НАЧАЛО луча сдвигаем в
 * позицию камеры (по {@code MODIFY_CAMERA_POS}), а НАПРАВЛЕНИЕ наклоняем (по
 * {@code MODIFY_CAMERA_ROT}). Общий миксин — работает на клиенте (подсказка/предсказание)
 * и на сервере (событие right-click приходит с обеих сторон). Когда наклона нет
 * ({@link TiltAccess} вернул null) — поведение ванильное.</p>
 */
@Mixin(value = RaycastHelper.class, remap = false)
public class CreateRaycastTiltMixin {

    // НАЧАЛО ЛУЧА здесь больше не правится (ветка experiment/clip-net): rayTraceRange
    // заканчивается ванильным level.clip() с origin, равным player.getEyePosition(), — это
    // ровно то, что должна забирать сеть (ClipNetMixin). Направление остаётся здесь: его
    // getTraceTarget считает от СЫРЫХ getXRot/getYRot, до сети такое не доходит.

    /** Направление луча → наклон под камеру (вокруг начала {@code origin}). */
    @Inject(method = "getTraceTarget", at = @At("RETURN"), cancellable = true)
    private static void aero$cameraDirection(Player player, double range, Vec3 origin,
                                             CallbackInfoReturnable<Vec3> cir) {
        Quaternionf lookTilt = TiltAccess.getLookTilt(player);
        if (lookTilt == null) return;

        Vec3 dir = cir.getReturnValue().subtract(origin);
        Vector3f v = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        lookTilt.transform(v);
        cir.setReturnValue(origin.add(v.x, v.y, v.z));
    }
}
