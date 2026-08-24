package com.playsi.aero_cam_sync;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Единая точка получения тилта игрока, работает на обеих сторонах.
 *
 * <p>На сервере тилт берётся из {@link ServerTiltStore} (синхронизируется клиентом).
 * На клиенте — из сглаженного тилта камеры локального игрока. Ссылка на клиентский
 * класс {@code ClientTiltAccess} исполняется только в ветке {@code isClientSide},
 * поэтому на выделенном сервере он не загружается.</p>
 */
public final class TiltAccess {

    private TiltAccess() {}

    /**
     * Квадрат порога «наклон значим» — общий для {@link ClipNet} и публичного API.
     *
     * <p>Одна константа на два определения намеренно. Сеть по нему решает, стоит ли вообще
     * трогать луч, а {@code AcsState.tiltApplied()} — считать ли наклон применённым; разъедься
     * они, мод получил бы {@code tiltApplied() == false} на луче, который мы всё ещё двигаем
     * (или наоборот). Тот же порог служит сети допуском на совпадение с точкой глаза —
     * там ожидается точное равенство, и это страховка.</p>
     */
    public static final double EPSILON_SQR = 1.0e-8;

    /** Тилт для НАКЛОНА НАПРАВЛЕНИЯ луча/взгляда (привязан к повороту камеры), либо null. */
    public static Quaternionf getLookTilt(Player player) {
        if (player == null) return null;
        if (player.level().isClientSide) {
            return com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.getClientLookTilt(player);
        }
        if (player instanceof ServerPlayer sp) {
            return ServerTiltStore.getLookTilt(sp.getUUID());
        }
        return null;
    }

    /** Тилт для СДВИГА НАЧАЛА луча (привязан к сдвигу позиции камеры), либо null. */
    public static Quaternionf getPosTilt(Player player) {
        if (player == null) return null;
        if (player.level().isClientSide) {
            return com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.getClientPosTilt(player);
        }
        if (player instanceof ServerPlayer sp) {
            return ServerTiltStore.getPosTilt(sp.getUUID());
        }
        return null;
    }

    /**
     * Насколько наклон камеры отодвигает точку глаза от настоящей, либо {@code null} —
     * вмешиваться не нужно.
     *
     * <p>Это тот же поворот точки глаза вокруг ног, который применяет камера, но выраженный
     * ДЕЛЬТОЙ: её достаточно прибавить к любой точке, чтобы перевести луч «от глаза» в луч
     * «от наклонённой камеры». Именно дельта, а не абсолютная позиция, потому что чужой код
     * часто собирает свой глаз по-своему (с интерполяцией, от {@code getEyeHeight()}, от
     * логической позиции) — прибавка сохраняет его расчёт и правит только наклон.</p>
     *
     * <p>Работает на обеих сторонах: на клиенте тилт берётся из камеры, на сервере — из
     * {@link ServerTiltStore}. Клиентский {@link com.playsi.aero_cam_sync.client.tilt.CameraController#aimEyeOffset(float)}
     * делает то же самое, но с {@code partialTick} — он для рендер-путей.</p>
     */
    public static Vec3 aimEyeOffset(Player player) {
        if (player == null) return null;

        // Под аварийным откатом на старый пик начало клипов на клиенте двигает ClipShifter —
        // если сдвинуть ещё и здесь, получится двойной сдвиг. Поведение отката обязано
        // совпадать с 1.3.6, где этих compat-правок не было вовсе.
        if (player.level().isClientSide) {
            if (com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isLegacyPick()) return null;
            // Те же исключения, что у воронки: третье лицо и рендерные пути Sable. Без этого
            // сеть двигала луч там, где воронка его намеренно не трогает, — ран 2026-08-06
            // поймал ванильный Entity#pick именно поэтому.
            if (!com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isAimShiftAllowed()) {
                return null;
            }
        }

        Quaternionf posTilt = getPosTilt(player);
        if (posTilt == null) return null;

        // Логическая поза, без partialTick: этот путь обслуживает тик и сервер.
        return eyeRotationDelta(player.getEyePosition(), player.position(), posTilt);
    }

    /**
     * ЕДИНСТВЕННАЯ копия арифметики сдвига глаза: поворот вектора роста вокруг ног, выраженный
     * дельтой.
     *
     * <p>Была написана трижды — здесь, в {@code CameraController.aimEyeOffset(float)} и в
     * {@code PickScopeMixin.tiltOffset(float)}. Копии не расходились в самой формуле, но именно
     * из-за них проверка 2026-08-16 дала неверный вывод: одну копию прочитали, решили, что
     * прочитали все, и не заметили, что начало луча в третьем лице берётся вообще не отсюда.
     * Три копии делают любую такую проверку недостоверной по построению.</p>
     *
     * <p>Аргументами взяты уже готовые {@code eye} и {@code feet}, а не игрок: вызывающие
     * различаются ВРЕМЕНЕМ (интерполированная поза кадра против логической позы тика), и это
     * различие настоящее. А вот формула у них одна, и теперь она одна физически.</p>
     *
     * <p>Считается заново, а не как {@code позиция камеры − глаз}: камера обновляется в
     * {@code Camera#setup}, то есть уже после пика, и разность тащила бы отставание на кадр.
     * Здесь же поворачивается короткий вектор роста глаза, и прошлокадровость кватерниона
     * даёт ошибку второго порядка.</p>
     */
    public static Vec3 eyeRotationDelta(Vec3 eye, Vec3 feet, Quaternionf posTilt) {
        Vector3f rel = new Vector3f(
                (float) (eye.x - feet.x),
                (float) (eye.y - feet.y),
                (float) (eye.z - feet.z));
        Vector3f tilted = posTilt.transform(new Vector3f(rel));
        return new Vec3(tilted.x - rel.x, tilted.y - rel.y, tilted.z - rel.z);
    }

    /**
     * Точка, из которой на самом деле смотрит игрок — глаз плюс {@link #aimEyeOffset}.
     * Если наклона нет, возвращает ванильный глаз.
     */
    public static Vec3 aimEyePosition(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 offset = aimEyeOffset(player);
        return offset == null ? eye : eye.add(offset);
    }
}
