package com.playsi.aero_cam_sync;

import net.minecraft.world.level.Level;

import java.util.function.Supplier;

/**
 * Сторона и поток для {@link ClipNet} — единственное место, где общий код заглядывает
 * в клиентские классы.
 *
 * <p>Ссылки на клиентский пакет исполняются только внутри ветки {@code isClientSide},
 * поэтому на выделенном сервере эти классы не загружаются — тот же приём, что в
 * {@link TiltAccess}.</p>
 */
final class SideGate {

    private SideGate() {}

    /**
     * Идёт ли вызов из «своего» потока этой стороны.
     *
     * <p>Прицел — логика главного потока. Фоновые лучи трогать нельзя: Sound Physics Perfected
     * трассирует звук из пула потоков через {@code world.clip()}, и вход туда даёт чтение
     * объектов главного потока из чужого и повторный вход в неатомарный clip Sable — клиент
     * вешался наглухо (Issue #24).</p>
     */
    static boolean isOwnThread(Level level) {
        if (level.isClientSide) {
            return com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isRenderThread();
        }
        return level.getServer() != null && level.getServer().isSameThread();
    }

    /**
     * Диагностика эксперимента: печатает КАЖДОГО нового пойманного вызывающего ровно один раз.
     * Только на клиенте — конфиг с галочкой клиентский.
     */
    static void reportCatch(Level level, Supplier<String> caller, double offsetLength) {
        if (!level.isClientSide) return;
        com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.reportClipNetCatch(caller, offsetLength);
    }
}
