package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.AeroCamSync;
import com.playsi.aero_cam_sync.api.AcsHandle;
import com.playsi.aero_cam_sync.api.AcsState;
import com.playsi.aero_cam_sync.api.AimPolicy;
import com.playsi.aero_cam_sync.api.TiltListener;
import com.playsi.aero_cam_sync.api.TiltSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/** Дескриптор одного мода. Создаётся ровно один раз на {@code modId} — см. {@link HandleRegistry}. */
final class AcsHandleImpl implements AcsHandle {

    /** Что считаем для сводки. Сами вызовы не логируются — это горячий путь. */
    enum Call { STATE, AIM_RAY, VANILLA_EYE }

    private final String modId;

    /** Не атомарные намеренно: статистика, а не состояние (см. {@link ApiLog#count}). */
    final long[] counters = new long[Call.values().length];

    AcsHandleImpl(String modId) {
        this.modId = modId;
        ApiLog.event(modId, "api handle acquired");
    }

    @Override public String modId() { return modId; }

    // ------------------------------------------------------------------ состояние

    @Override
    public AcsState state(Player player, float partialTick) {
        Objects.requireNonNull(player, "player");
        ApiLog.count(this, Call.STATE);
        return AcsStateImpl.capture(this, player, partialTick);
    }

    // ---------------------------------------------------------------- подавление

    @Override
    public void suppress(long millis) {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            ApiLog.warn(modId, "suppress() does nothing on a dedicated server — the tilt is computed client-side");
            return;
        }
        if (millis <= 0) return;

        if (millis > SuppressionLeases.LONG_LEASE_MILLIS) {
            // Запрещать не за что — «мой режим камеры не дружит с наклоном» законный сценарий,
            // но в логе это обязано быть видно.
            ApiLog.warn(modId, "suppression lease of {} ms is longer than {} ms", millis,
                    SuppressionLeases.LONG_LEASE_MILLIS);
        }
        ApiLog.event(modId, "tilt suppressed for {} ms", millis);
        SuppressionLeases.suppress(modId, millis);
    }

    @Override
    public void release() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) return;
        ApiLog.event(modId, "suppression released");
        SuppressionLeases.release(modId);
    }

    @Override public boolean isSuppressed() { return SuppressionLeases.isSuppressed(); }
    @Override public boolean isSuppressedByMe() { return SuppressionLeases.isSuppressedBy(modId); }

    // -------------------------------------------------------- коллизия камеры

    @Override
    public void disableCameraCollision(String reason) {
        // Проверка ПЕРЕД проверкой стороны и намеренно: пустая причина — ошибка программиста,
        // а не пользователя, и прятать её на выделенном сервере значит отдать её игроку.
        // Ровно как forMod с пустым modId.
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "AcsHandle.disableCameraCollision: reason must not be null or blank"
                    + " — it is the only thing that answers \"why does this camera clip\" in a log");
        }
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            ApiLog.warn(modId, "disableCameraCollision() does nothing on a dedicated server"
                    + " — camera collision is computed client-side");
            return;
        }

        // Строка в логе — с момента старта, воспроизводить ничего не нужно. Печатаем только на
        // первой регистрации: повторный вызов идемпотентен и событием не является.
        if (CameraCollisionOverrides.disable(modId, reason)) {
            ApiLog.event(modId, "camera collision disabled: {}", reason);
        }
    }

    @Override
    public void enableCameraCollision() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) return;
        // Симметричная строка обязательна: без неё непонятно, когда защита вернулась.
        if (CameraCollisionOverrides.enable(modId)) {
            ApiLog.event(modId, "camera collision re-enabled");
        }
    }

    @Override public boolean isCameraCollisionDisabled() {
        return CameraCollisionOverrides.isDisabled();
    }

    @Override public boolean isCameraCollisionDisabledByMe() {
        return CameraCollisionOverrides.isDisabledBy(modId);
    }

    // ----------------------------------------------------------- третье лицо

    @Override
    public void enableThirdPerson(String reason) {
        // Как в disableCameraCollision: пустая причина — ошибка программиста, и прятать её на
        // выделенном сервере значит отдать её игроку. Поэтому проверка ПЕРЕД проверкой стороны.
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "AcsHandle.enableThirdPerson: reason must not be null or blank"
                    + " — it is the only thing that answers \"who turned third person on\" in a log");
        }
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            ApiLog.warn(modId, "enableThirdPerson() does nothing on a dedicated server"
                    + " — the tilt in third person is computed client-side");
            return;
        }

        if (ThirdPersonOverrides.enable(modId, reason)) {
            ApiLog.event(modId, "third person enabled: {}", reason);
        }
    }

    @Override
    public void disableThirdPerson() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) return;
        // Симметричная строка обязательна: без неё непонятно, когда третье лицо снова стало
        // ванильным — а это ровно тот вопрос, с которым приходят «у меня прицел уехал».
        if (ThirdPersonOverrides.disable(modId)) {
            ApiLog.event(modId, "third person disabled");
        }
    }

    @Override public boolean isThirdPersonEnabled() {
        return ThirdPersonOverrides.isEnabled();
    }

    @Override public boolean isThirdPersonEnabledByMe() {
        return ThirdPersonOverrides.isEnabledBy(modId);
    }

    // ------------------------------------------------------------- ванильный глаз

    @Override
    public void withVanillaEye(Runnable body) {
        Objects.requireNonNull(body, "body");
        withVanillaEye(() -> { body.run(); return null; });
    }

    @Override
    public <T> T withVanillaEye(Supplier<T> body) {
        Objects.requireNonNull(body, "body");
        ApiLog.count(this, Call.VANILLA_EYE);

        // Счётчик внутри RenderEyeScope статический, не ThreadLocal, и таким остаётся: прицел
        // живёт в рендер-потоке, а фоновые лучи мы туда намеренно не пускаем (Issue #24).
        // Открыть окно из чужого потока значило бы выключить наклон у главного.
        if (!isClientMainThread()) {
            ApiLog.warn(modId, "withVanillaEye() called off the client main thread"
                    + " — the body runs, but without the scope");
            return body.get();
        }

        com.playsi.aero_cam_sync.client.aim.RenderEyeScope.enter();
        try {
            return body.get();
        } finally {
            com.playsi.aero_cam_sync.client.aim.RenderEyeScope.exit();
        }
    }

    private static boolean isClientMainThread() {
        if (FMLEnvironment.dist != Dist.CLIENT) return false;
        return com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isRenderThread();
    }

    // ---------------------------------------------------------- точки расширения

    @Override
    public void addListener(TiltListener listener) {
        Objects.requireNonNull(listener, "listener");
        ApiLog.event(modId, "tilt listener registered");
        TiltListeners.add(modId, listener);
    }

    @Override
    public void addPolicy(AimPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        ApiLog.event(modId, "aim policy registered");
        AimPolicies.add(modId, policy);
    }

    @Override
    public void addTiltSource(int priority, TiltSource source) {
        Objects.requireNonNull(source, "source");
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            ApiLog.warn(modId, "addTiltSource() does nothing on a dedicated server"
                    + " — the tilt is computed client-side");
            return;
        }
        // Приоритет — в строке лога намеренно: «почему мой источник не срабатывает» почти
        // всегда означает «выше зарегистрировался кто-то ещё», и ответ на это должен быть
        // виден до того, как мододел придёт спрашивать.
        //
        // И он вклеен в САМ ФОРМАТ, а не подставлен аргументом, — вопреки обычному правилу
        // ApiLog. Ключ дедупликации там формат без значений, поэтому мод, зарегистрировавший
        // два источника с разными приоритетами, увидел бы ровно одну строку и половину своей
        // настройки молча потерял. Захлебнуться на этом нельзя: регистраций конечное число,
        // это не горячий путь ClipNet, на котором правило и появилось.
        ApiLog.event(modId, "tilt source registered (priority " + priority + ")");
        TiltSources.add(modId, priority, source);
    }

    // ------------------------------------------------------------------- сводка

    /**
     * Строка сводки раз в тридцать секунд под {@code DEBUG_MESSAGES}. Без неё «мод спрашивает
     * состояние сорок тысяч раз за кадр» не всплывёт никогда.
     */
    void logSummaryAndReset() {
        long state = counters[Call.STATE.ordinal()];
        long aimRay = counters[Call.AIM_RAY.ordinal()];
        long vanillaEye = counters[Call.VANILLA_EYE.ordinal()];
        if ((state | aimRay | vanillaEye) == 0L) return;

        AeroCamSync.LOGGER.info("[AeroCamSync] * {}: state() ×{}, aimRay() ×{}, withVanillaEye() ×{}",
                modId, state, aimRay, vanillaEye);
        Arrays.fill(counters, 0L);
    }
}
