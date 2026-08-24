package com.playsi.aero_cam_sync.mixins.compat;

import com.playsi.aero_cam_sync.TiltAccess;
import dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueClientHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Honey glue (Create Simulated): собственные лучи предмета — из наклонённой камеры.
 *
 * <p><b>Симптом.</b> Клей встаёт туда, куда целишься, а жёлтая рамка предпросмотра стоит
 * рядом с перекрестием, а не под ним; наведение на уже поставленный клей (подсветка,
 * ctrl+скролл, удаление левым кликом) промахивается тем сильнее, чем больше крен.</p>
 *
 * <p><b>Почему установка верна, а всё остальное нет.</b> Позиция для спавна приходит из
 * события {@code PlayerInteractEvent.RightClickBlock} — то есть из {@code mc.hitResult},
 * который мы уже держим верным. А вот подсказку и наведение {@code HoneyGlueClientHandler}
 * считает ДВУМЯ своими лучами, и оба собраны наполовину: начало — ванильный
 * {@code player.getEyePosition()}, направление — {@code getViewVector} / {@code getTraceTarget},
 * которые мы наклоняем глобально. Классический гибридный луч (§1.6), только в чужом моде.</p>
 *
 * <p><b>Почему их не забрала сеть ({@code ClipNet}) — два разных повода:</b></p>
 * <ul>
 *   <li>{@code getHitResult} до {@code Level#clip} доходит, но строит {@code ClipContext} с
 *       {@code CollisionContext.empty()}. Это {@code EntityCollisionContext.EMPTY} с
 *       {@code entity == null}, и проверка владельца в {@code ClipNet} отваливается: чей это
 *       луч, из контекста не видно. Сама точка старта под фильтр сети подошла бы;</li>
 *   <li>{@code updateHovered} клипает {@code AABB} напрямую, {@code Level#clip} не зовётся
 *       вовсе — сеть до такого не достаёт по построению (§1.8).</li>
 * </ul>
 *
 * <p>Правим начало обоих лучей одной и той же дельтой. Направление трогать не надо — оно уже
 * наклонено, и {@code RaycastHelper.getTraceTarget} в {@code updateHovered} наклоняет его
 * ВОКРУГ переданного начала, то есть подхватывает сдвинутое.</p>
 *
 * <p><b>Двойного сдвига не будет:</b> после правки луч {@code getHitResult} начинается в
 * «глаз + дельта» и в фильтр сети («ровно в ванильном глазу») уже не попадает. Под откатом
 * {@code LEGACY_PICK} {@link TiltAccess#aimEyeOffset} возвращает {@code null}, и оба луча
 * остаются в точности такими, какими были в 1.3.6 (там клип двигал {@code ClipShifter}).</p>
 *
 * <p><b>{@code onScroll} намеренно не тронут.</b> Там точка глаза служит не лучом, а проверкой
 * «камера внутри бокса» — от неё зависит только знак прокрутки. Дельта меньше блока и меняет
 * ответ лишь у самой грани; заодно этот метод единственный считает через {@code renderPose},
 * тогда как {@code updateHovered} — через {@code logicalPose}, и мешать эти системы без нужды
 * не стоит.</p>
 */
@Mixin(value = HoneyGlueClientHandler.class, remap = false)
public abstract class SimulatedHoneyGlueEyeMixin {

    /**
     * Начало обоих лучей предмета. {@code getHitResult} зовёт глаз дважды (начало и конец
     * {@code ClipContext}) — редирект без {@code ordinal} накрывает оба, поэтому луч сдвигается
     * параллельно, а не растягивается.
     */
    @Redirect(
            method = {"getHitResult", "updateHovered"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getEyePosition()Lnet/minecraft/world/phys/Vec3;",
                    remap = true),
            require = 0
    )
    private Vec3 aero$tiltedEye(Player player) {
        return TiltAccess.aimEyePosition(player);
    }
}
