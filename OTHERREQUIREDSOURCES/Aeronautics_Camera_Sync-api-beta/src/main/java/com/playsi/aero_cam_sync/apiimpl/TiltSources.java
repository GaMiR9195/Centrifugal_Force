package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.TiltContext;
import com.playsi.aero_cam_sync.api.TiltSource;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Реестр источников наклона и разрешение спора между ними.
 *
 * <h2>Почему первый claim выигрывает целый кадр, а не «складываются»</h2>
 *
 * <p>Наклон один по построению: его читают камера, перекрестие, все лучи, снаряды и
 * {@code TiltSync}, отправляющий его на сервер. Два мода, по очереди правящие камеру внутри
 * кадра, дают ровно тот гибрид (§1.6), ради устранения которого писалось всё остальное.
 * Поэтому кадр достаётся одному, а «сложить своё поверх чужого» выражается иначе — через
 * {@code TiltContext.acsTilt()}: источник берёт готовое значение и возвращает своё.</p>
 *
 * <h2>Почему ACS не лежит в списке</h2>
 *
 * <p>Он не участник спора, а то, что происходит, когда спорить некому: {@link #resolve} отдаёт
 * {@code null}, и {@code CameraController} оставляет собственный наклон нетронутым. Положи мы
 * ACS в список отдельной записью с минимальным приоритетом, появилась бы возможность
 * зарегистрироваться НИЖЕ него — то есть никогда не сработать, — и это был бы молчаливый
 * дефект у мододела.</p>
 *
 * <h2>⚠ Выход из мира НЕ сбрасывает</h2>
 *
 * <p>Как у {@link ThirdPersonOverrides} и {@link CameraCollisionOverrides} и по той же причине:
 * регистрация — свойство мода, а не сессии. Мод регистрирует источник один раз на старте.</p>
 */
public final class TiltSources {

    private TiltSources() {}

    /** Кто выиграл кадр и с каким наклоном. */
    public record Winner(String modId, Quaternionf tilt) {}

    private record Registered(String modId, int priority, long order, TiltSource source) {}

    /**
     * Отсортированный список: приоритет по убыванию, при равенстве — порядок регистрации.
     *
     * <p>Неизменяемый и целиком заменяемый: регистрация редка (раз за сессию на мод), а чтение
     * идёт каждый кадр из рендер-потока. Копирование при записи дешевле любой синхронизации на
     * чтении. Сортировка живёт здесь, а не в {@link #resolve}, по той же причине.</p>
     */
    private static volatile List<Registered> sources = List.of();

    /** Счётчик регистраций — им разрешается равенство приоритетов. Под тем же замком, что запись. */
    private static long registrations = 0L;

    // -------------------------------------------------------------------- API

    public static synchronized void add(String modId, int priority, TiltSource source) {
        List<Registered> next = new ArrayList<>(sources);
        next.add(new Registered(modId, priority, registrations++, source));
        next.sort(Comparator.comparingInt(Registered::priority).reversed()
                .thenComparingLong(Registered::order));
        sources = List.copyOf(next);
    }

    /** Есть ли вообще кого спрашивать. Одна проверка — столько платят моды без источников. */
    public static boolean isEmpty() {
        return sources.isEmpty();
    }

    /**
     * Спрашивает источники по убыванию приоритета и отдаёт первого, кто взял кадр.
     *
     * <p>Остальные НЕ опрашиваются — в отличие от {@code AimPolicies}, где полный обход нужен
     * ради строки о споре. Здесь спорить не о чем: приоритет и есть ответ, кто главнее, а
     * лишний вызов чужого кода на кадровом пути платится зря.</p>
     *
     * @return победитель, либо {@code null} — наклон остаётся за ACS
     */
    @Nullable
    public static Winner resolve(Player player, float partialTick, float deltaTicks,
                                 @Nullable Vector3f surfaceNormal, Quaternionf acsTilt,
                                 boolean firstPerson) {
        List<Registered> list = sources;
        if (list.isEmpty()) return null;

        TiltContext context = new TiltContextImpl(
                player, partialTick, deltaTicks, surfaceNormal, acsTilt, firstPerson);

        for (Registered r : list) {
            boolean claims;
            try {
                claims = r.source().appliesTo(context);
            } catch (Throwable t) {
                // Упавший предикат — это «не претендую»: уронить с ним весь кадр камеры значит
                // превратить чужой баг в чёрный экран у игрока.
                ApiLog.warn(r.modId(), "tilt source predicate threw, treated as declined: {}",
                        String.valueOf(t));
                continue;
            }
            if (!claims) continue;

            Quaternionf tilt;
            try {
                tilt = r.source().tilt(context);
            } catch (Throwable t) {
                ApiLog.warn(r.modId(), "tilt source threw, frame passed to the next source: {}",
                        String.valueOf(t));
                continue;
            }

            if (tilt == null) {
                ApiLog.warn(r.modId(), "tilt source claimed the frame but returned null"
                        + " — appliesTo and tilt disagree; frame passed to the next source");
                continue;
            }

            // Ноль-кватернион даёт NaN на normalize(), а NaN в наклоне — это исчезнувший мир,
            // и искать его источник игрок будет не у того мода.
            float lengthSqr = tilt.lengthSquared();
            if (!Float.isFinite(lengthSqr) || lengthSqr < 1.0e-12f) {
                ApiLog.warn(r.modId(), "tilt source returned a degenerate quaternion,"
                        + " frame passed to the next source");
                continue;
            }

            // Раз за сессию на мод — по этой строке в чужом логе видно, кто забрал наклон.
            ApiLog.event(r.modId(), "tilt source took the frame (priority {})", r.priority());

            return new Winner(r.modId(), new Quaternionf(tilt).normalize());
        }

        return null;
    }
}
