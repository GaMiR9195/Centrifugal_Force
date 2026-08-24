package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.TiltContext;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Вход одного кадра для источников наклона.
 *
 * <p>Собирается один раз за кадр в {@link TiltSources#resolve} и переживает ровно опрос —
 * геттеры отдают копии, чтобы источник не мог испортить общее состояние, случайно вызвав
 * на выданном кватернионе мутирующий метод joml (там мутирует почти всё).</p>
 *
 * <p><b>Ни одного клиентского типа.</b> Интерфейс {@code TiltSource} мод регистрирует через
 * общий {@code AcsHandle}, значит и он, и всё в его сигнатурах загружается на выделенном
 * сервере. Саблевела здесь поэтому нет — и не будет: кому он нужен, тот и так работает с
 * Sable напрямую, а нам за него ловить краш хендшейка (Issue #33).</p>
 */
record TiltContextImpl(Player player,
                       float partialTick,
                       float deltaTicks,
                       @Nullable Vector3f normal,
                       Quaternionf acs,
                       boolean firstPerson) implements TiltContext {

    @Override
    public @Nullable Vector3f surfaceNormal() {
        return normal == null ? null : new Vector3f(normal);
    }

    @Override
    public Quaternionf acsTilt() {
        return new Quaternionf(acs);
    }
}
