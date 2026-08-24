package com.playsi.aero_cam_sync.client.tilt;

import com.playsi.aero_cam_sync.client.aim.RenderEyeScope;
import com.playsi.aero_cam_sync.client.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;

/**
 * Клиентский поставщик тилта для {@link com.playsi.aero_cam_sync.TiltAccess}.
 *
 * <p>Тилт отдаётся только для ЛОКАЛЬНОГО игрока — взаимодействия (вёдра, снаряды
 * через {@code getPlayerPOVHitResult}) выполняются именно им. Условия совпадают с
 * клиентским наклоном камеры, чтобы предсказание клиента совпадало с авторитетным
 * результатом сервера.</p>
 */
public final class ClientTiltAccess {

    private ClientTiltAccess() {}

    /**
     * Включён ли аварийный откат на старый пик.
     *
     * <p>Живёт здесь, потому что {@code Config} — клиентский, а спрашивают об этом общие
     * миксины ({@code ItemPovTiltMixin}, {@code CreateRaycastTiltMixin}): они обязаны
     * дотягиваться до клиентской настройки только внутри ветки {@code isClientSide},
     * как и до самого тилта.</p>
     */
    public static boolean isLegacyPick() {
        return Config.isLoaded() && Config.LEGACY_PICK.get();
    }

    /** Включён ли мод — конфигом и тумблером (это одно и то же значение). */
    public static boolean isModEnabled() {
        return Config.isLoaded() && Config.MOD_ENABLED.get();
    }

    /**
     * Галочка отладочных сообщений. Живёт здесь по той же причине, что и
     * {@link #isLegacyPick()}: {@code Config} клиентский, а спрашивает об этом общий
     * {@code apiimpl.ApiLog} — дотягиваться до клиентского пакета он обязан только
     * внутри проверки стороны.
     */
    public static boolean isDebugMessages() {
        return Config.isLoaded() && Config.DEBUG_MESSAGES.get();
    }

    /** Главный (рендер) поток клиента — см. {@code SideGate#isOwnThread}. */
    public static boolean isRenderThread() {
        return Minecraft.getInstance().isSameThread();
    }

    /**
     * Разрешено ли сейчас вообще двигать начало прицельного луча на клиенте.
     *
     * <p>Условия ровно те же, при которых поправку добавляет воронка
     * ({@code SableEyeMixin} → {@link CameraController#aimEyeOffset(float)}). Пока их не было,
     * сеть ({@code ClipNet}) расходилась с воронкой: ран 2026-08-06 поймал ванильный
     * {@code Entity#pick} — луч, которому поправка уже положена от воронки, а в исключениях
     * (третье лицо, рендерные пути Sable) не положена вовсе. Сеть же прибавляла её всегда.</p>
     *
     * <p>Два источника одной поправки обязаны включаться и выключаться синхронно, иначе
     * получается ровно тот гибрид, ради устранения которого всё и делалось (§1.6).</p>
     */
    public static boolean isAimShiftAllowed() {
        // Рендерные пути Sable, где нужен НАСТОЯЩИЙ глаз (освещение, отсечение сущностей).
        if (RenderEyeScope.isActive()) return false;
        // Первое лицо — всегда; третье — только если его включил чужой мод через API.
        // Спрашиваем ОБЩИЙ гейт, а не режим камеры: у него снимок на кадр, поэтому все точки
        // прицела отвечают одинаково даже если мод флипнул переключатель посреди кадра.
        return Minecraft.getInstance().options.getCameraType().isFirstPerson()
                || CameraController.isThirdPersonAllowed();
    }

    /**
     * ВРЕМЕННО (ветка experiment/clip-net): печатает каждого нового вызывающего, чей луч
     * сдвинула сеть. Ровно один раз на вызывающего — иначе лог захлебнётся, клип зовут
     * десятки раз за кадр.
     */
    public static void reportClipNetCatch(java.util.function.Supplier<String> caller,
                                          double offsetLength) {
        if (!ClientTiltAccess.isDebugMessages()) return;

        String name = caller.get();
        if (!com.playsi.aero_cam_sync.ClipNet.firstTimeSeen(name)) return;

        com.playsi.aero_cam_sync.AeroCamSync.LOGGER.info(
                "[AeroCamSync] clip-net поймала: {} | offset={}", name,
                String.format("%.3f", offsetLength));
    }

    /**
     * Тилт направления — привязан к повороту камеры.
     *
     * <p>Условие спрашивается у {@link CameraController#rotationActive()}, а не у настройки
     * напрямую: под чужим источником настройка наклон не режет — см. javadoc того метода.
     * Читать её здесь отдельно значило бы развести луч и камеру, то есть вернуть долг 12.3.</p>
     */
    public static Quaternionf getClientLookTilt(Player player) {
        if (!baseAllows(player)) return null;
        if (!CameraController.rotationActive()) return null;
        return CameraController.getSmoothedTilt();
    }

    /** Тилт сдвига начала луча — привязан к сдвигу позиции камеры. Зеркало метода выше. */
    public static Quaternionf getClientPosTilt(Player player) {
        if (!baseAllows(player)) return null;
        if (!CameraController.posShiftActive()) return null;
        return CameraController.getSmoothedTilt();
    }

    private static boolean baseAllows(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != player) return false;
        if (!Config.isLoaded()) return false;
        if (!Config.MOD_ENABLED.get()) return false;
        return CameraController.shouldApplyTilt();
    }
}
