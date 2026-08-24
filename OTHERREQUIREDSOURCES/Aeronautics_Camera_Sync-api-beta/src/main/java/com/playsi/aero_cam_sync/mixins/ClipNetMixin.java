package com.playsi.aero_cam_sync.mixins;

import com.playsi.aero_cam_sync.ClipNet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Точка входа «сети» — см. {@link ClipNet}, там вся логика и обоснование фильтра.
 *
 * <p>Висит на {@code BlockGetter#clip}, потому что именно этот default-метод интерфейса
 * зовут все — и ваниль, и моды (у {@code Level} своего переопределения нет).</p>
 *
 * <p><b>{@code priority = 1200}</b> — как у старого {@code legacy/SableCompatClipMixin}:
 * инжекторы применяются по убыванию приоритета, и на HEAD нам надо оказаться раньше
 * перехвата Sable, чтобы отдать ему уже сдвинутый луч, а не сдвигать его результат.</p>
 *
 * <p>Логика вынесена в обычный класс намеренно: тело default-метода интерфейса копируется
 * в целевой интерфейс, и держать там ветвление по стороне и потоку неудобно и опасно.</p>
 */
@Mixin(value = BlockGetter.class, priority = 1200)
public interface ClipNetMixin {

    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    default void aero$shiftAimingRay(ClipContext context,
                                     CallbackInfoReturnable<BlockHitResult> cir) {
        // Ни одно исключение отсюда не должно ронять чужой рейкаст: сеть — вспомогательный
        // слой, а clip зовут из мест, где падение фатально (физика, AI, рендер).
        try {
            BlockHitResult shifted = ClipNet.tryShift((BlockGetter) this, context);
            if (shifted != null) cir.setReturnValue(shifted);
        } catch (Throwable ignored) { }
    }
}
