package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.AeroCamSync;
import com.playsi.aero_cam_sync.api.AcsState;
import com.playsi.aero_cam_sync.api.TiltListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реестр слушателей наклона.
 *
 * <p>{@link CopyOnWriteArrayList}: пишется раз за загрузку, читается из событий кадра —
 * ровно тот профиль, под который он и сделан.</p>
 *
 * <p>Типов Minecraft здесь нет ни одного (события подавления оперируют строками), поэтому
 * {@link SuppressionLeases} может звать этот класс напрямую, не ломая свой юнит-тест.</p>
 */
public final class TiltListeners {

    private TiltListeners() {}

    private record Registered(String modId, TiltListener listener) {}

    private static final CopyOnWriteArrayList<Registered> LISTENERS = new CopyOnWriteArrayList<>();

    public static void add(String modId, TiltListener listener) {
        LISTENERS.add(new Registered(modId, listener));
    }

    /** Никто не подписан — событие можно даже не собирать (снимок стоит аллокации). */
    public static boolean isEmpty() {
        return LISTENERS.isEmpty();
    }

    public static void tiltStarted(AcsState state) {
        for (Registered r : LISTENERS) {
            guard(r, () -> r.listener().onTiltStart(state));
        }
    }

    public static void tiltStopped(AcsState state) {
        for (Registered r : LISTENERS) {
            guard(r, () -> r.listener().onTiltStop(state));
        }
    }

    public static void suppressionChanged(boolean suppressed, List<String> by) {
        for (Registered r : LISTENERS) {
            guard(r, () -> r.listener().onSuppressionChanged(suppressed, by));
        }
    }

    /**
     * Исключение в чужом слушателе не должно ронять наш кадр: события уходят из
     * {@code Camera#setup} и из клиентского тика, где падение фатально.
     */
    private static void guard(Registered r, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            AeroCamSync.LOGGER.error("[AeroCamSync] * {}: tilt listener threw", r.modId(), t);
        }
    }
}
