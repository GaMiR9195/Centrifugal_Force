package com.playsi.aero_cam_sync;

import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess;

/**
 * Состояние стороны на текущую сессию: работаем ли мы в одиночку на клиенте или сервер тоже
 * знает про мод.
 *
 * <p><b>Только состояние.</b> Отправка наклона жила здесь же и утащена в
 * {@code client.tilt.TiltSync} (долг 12.7): из-за неё этот класс тянул {@code Minecraft} и
 * {@code CameraController}, то есть был клиентским по зависимостям, оставаясь общим по
 * расположению.</p>
 *
 * <p>Спрашивают отсюда клиентские пути и обработка хендшейка. Клиентская настройка
 * {@code IGNORE_SERVER} читается только через {@code Config.isLoaded()} — на выделенном сервере
 * её спека не зарегистрирована, а {@code .get()} до загрузки бросает
 * {@code IllegalStateException} (Issue #19, #33).</p>
 */
public class SideManager {

    public enum Side {
        UNKNOWN,
        CLIENT_ONLY,
        CLIENT_SERVER
    }

    private static Side currentSide = Side.UNKNOWN;

    /**
     * Значение {@link Config#IGNORE_SERVER}, зафиксированное на входе в мир, на всю сессию.
     *
     * <p>Опцию НЕЛЬЗЯ читать вживую. На сервере с модом переключение прямо в игре
     * мгновенно уводит клиент в клиент-онли ветку ({@code AUTO_DISABLE_FOR_RAYCAST_ITEMS}
     * выравнивает камеру, предсказание идёт без наклона) и одновременно глушит отправку
     * наклона — а сервер продолжает крутить снаряды/взгляд по ПОСЛЕДНЕМУ полученному тилту,
     * потому что {@link ServerTiltStore} никто не чистит. Клиент целится прямо, сервер стреляет
     * под старым наклоном → траектории расходятся. Поэтому смена режима применяется только на
     * следующем заходе в мир.</p>
     */
    private static boolean ignoreServerSession = false;

    public static Side getSide() {
        return currentSide;
    }

    public static void setSide(Side side) {
        // Через аксессор, а не Config.DEBUG_MESSAGES.get() напрямую: раньше здесь единственным
        // из четырёх логов не было проверки isLoaded(), то есть ранний вызов бросал бы
        // IllegalStateException.
        if (ClientTiltAccess.isDebugMessages()) {
            AeroCamSync.LOGGER.info("[AeroCamSync] SideManager -> {}", side);
        }
        currentSide = side;
    }

    /** Зафиксированный на сессию режим «только клиент» (см. {@link #ignoreServerSession}). */
    public static boolean isIgnoreServerSession() {
        return ignoreServerSession;
    }

    /**
     * {@code true}, если игрок переключил «только на клиенте» уже в мире:
     * настройка сохранена, но вступит в силу лишь при следующем заходе.
     * Используется только для подсказки в экране настроек.
     */
    public static boolean isIgnoreServerPending() {
        return currentSide != Side.UNKNOWN
                && Config.isLoaded()
                && Config.IGNORE_SERVER.get() != ignoreServerSession;
    }

    public static boolean isClientOnly() {
        if (ignoreServerSession) {
            return true;
        }

        return currentSide != Side.CLIENT_SERVER;
    }

    public static boolean isClientServer() {
        if (ignoreServerSession) {
            return false;
        }

        return currentSide == Side.CLIENT_SERVER;
    }

    /**
     * Начало сессии (вход в мир/на сервер): сбрасываем сторону и фиксируем
     * «только на клиенте» на всю сессию.
     */
    public static void beginSession() {
        currentSide = Side.UNKNOWN;
        latchIgnoreServer();
        if (ClientTiltAccess.isDebugMessages()) {
            AeroCamSync.LOGGER.info("[AeroCamSync] Session started, clientOnly latched = {}", ignoreServerSession);
        }
    }

    public static void reset() {
        if (ClientTiltAccess.isDebugMessages()) {
            AeroCamSync.LOGGER.info("[AeroCamSync] SideManager reset (disconnect)");
        }
        currentSide = Side.UNKNOWN;
        // Вне мира держим фиксацию актуальной: подсказка «нужен перезаход» гаснет,
        // а следующий заход всё равно перечитает конфиг в beginSession().
        latchIgnoreServer();
    }

    private static void latchIgnoreServer() {
        // Конфиг клиентский: на выделенном сервере его спека не зарегистрирована,
        // а .get() до загрузки бросает IllegalStateException (Issue #19, #33).
        if (Config.isLoaded()) {
            ignoreServerSession = Config.IGNORE_SERVER.get();
        }
    }
}
