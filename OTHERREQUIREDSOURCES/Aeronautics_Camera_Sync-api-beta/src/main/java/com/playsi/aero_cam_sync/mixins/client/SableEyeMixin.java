package com.playsi.aero_cam_sync.mixins.client;

import com.playsi.aero_cam_sync.client.debug.PickDiagnostics;
import com.playsi.aero_cam_sync.client.tilt.CameraController;
import com.playsi.aero_cam_sync.client.aim.PickScope;
import com.playsi.aero_cam_sync.client.aim.RenderEyeScope;
import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Единственное место, где ACS сообщает миру, откуда игрок смотрит.
 *
 * <p>{@code ActiveSableCompanion#getEyePositionInterpolated} — воронка, через которую в
 * Sable 2.0.3 проходят ВСЕ лучи «от глаза» на клиенте:</p>
 * <ul>
 *   <li>ванильный блочный луч — {@code Entity#pick} через {@code clip_overwrite/EntityMixin};</li>
 *   <li>ванильный сущностный луч — {@code GameRenderer#pick(Entity,DDF)} через
 *       {@code clip_overwrite/GameRendererMixin};</li>
 *   <li>обводка треков Create — {@code TrackBlockOutlineMixin};</li>
 *   <li>throttle lever из Create Simulated — зовёт хелпер напрямую;</li>
 *   <li>Cut Through — работает внутри {@code pick} и наследует его origin.</li>
 * </ul>
 *
 * <p>Поэтому одной подмены здесь достаточно, чтобы чужой пик поехал за перекрестием сам,
 * без единой строчки про конкретные моды. Физика сторонних модов (Bits'n'Tracks, Issue #30)
 * сюда не заходит вовсе — она не спрашивает глаз игрока.</p>
 *
 * <p><b>Гейт обязателен, но он ИНВЕРТИРОВАН (2026-08-03).</b> Раньше поправка применялась
 * только внутри {@link PickScope} — и код вне пика получал гибрид: направление
 * ({@code getViewVector}) тилтится глобально, а начало нет. Такой луч не совпадает ни с
 * перекрестием, ни с ванилой; на этом ловились и рычаг газа Simulated (§1.2), и клип модулей
 * Dashpanels в рендерере. Теперь наоборот: тилтим везде, а исключения перечислены в
 * {@link RenderEyeScope} — два рендерных пути Sable, где нужен настоящий глаз.</p>
 *
 * <p>Проверка потока остаётся по прежней причине: фоновые лучи Sound Physics Perfected
 * трогать нельзя.</p>
 */
@Mixin(value = ActiveSableCompanion.class, remap = false)
public abstract class SableEyeMixin {

    @Inject(method = "getEyePositionInterpolated", at = @At("RETURN"), cancellable = true)
    private void aero$tiltedPickOrigin(Entity entity, float partialTicks,
                                       CallbackInfoReturnable<Vec3> cir) {
        // ИНВЕРСИЯ ГЕЙТА (2026-08-03). Было «тилтим только внутри PickScope» — и любой код
        // вне пика получал гибрид: направление тилтится глобально (EntityLookMixin), а начало
        // нет. Стало «тилтим всегда, кроме явно перечисленных рендерных путей Sable».
        if (RenderEyeScope.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        if (entity != mc.player) return;

        Vec3 offset = CameraController.aimEyeOffset(partialTicks);
        if (offset == null) return;

        // RETURN, а не HEAD: нам нужен глаз, который Sable уже собрал против renderPose
        // ТЕКУЩЕГО кадра.
        //
        // Почему прибавляем ПОПРАВКУ, а не подставляем позицию камеры целиком. Для игрока на
        // саблевеле Sable собирает глаз в локальной системе палубы и надевает renderPose этого
        // кадра. Позиция камеры посчитана в Camera#setup ПРОШЛОГО кадра, то есть против прошлой
        // позы, и подстановка её целиком уводила origin на смещение палубы за кадр: на дрейфующей
        // палубе обводка оставалась верной, а ломался соседний блок. Поправка же — короткий
        // вектор поворота глаза вокруг ног, и разница поз за кадр на нём второго порядка малости.
        Vec3 sableEye = cir.getReturnValue();
        if (sableEye == null) return;

        Vec3 tilted = sableEye.add(offset);

        // Насколько новая точка разошлась со старой (позицией камеры) — та самая величина, на
        // которую прошлокадровая поза уводила луч. Гейт отладки внутри: этот миксин применяется
        // и в релизе, а зовут его на каждый луч «от глаза».
        PickDiagnostics.recordEyeDelta(PickScope.origin(), tilted);

        PickScope.countSubstitution();
        cir.setReturnValue(tilted);
    }
}
