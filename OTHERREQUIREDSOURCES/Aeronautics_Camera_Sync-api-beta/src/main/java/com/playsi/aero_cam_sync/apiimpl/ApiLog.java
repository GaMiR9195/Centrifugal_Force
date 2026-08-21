package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.AeroCamSync;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Лог всего, что инициировал чужой мод.
 *
 * <p><b>Звёздочка и {@code modId} — обязательны.</b> В баг-репорте «ACS сломал мне мод» первая
 * строка лога обязана отвечать, кто вообще к нам ходил; без пометки наши собственные строки и
 * чужие вызовы неразличимы.</p>
 *
 * <pre>
 * [AeroCamSync] * mymod: api handle acquired
 * [AeroCamSync] * mymod: tilt suppressed for 250 ms
 * </pre>
 *
 * <p>Формат собран целиком здесь, а не по кускам в каждом месте вызова, — иначе он разъедется
 * за две недели.</p>
 *
 * <h2>Дедупликация — по шаблону, не по готовой строке</h2>
 *
 * <p>Ключ уникальности — {@code modId} плюс сам формат сообщения, БЕЗ подставленных значений.
 * Ровно на этом горел улов {@code ClipNet}: величина поправки попала в ключ, и Cut Through дал
 * три десятка одинаковых по смыслу строк. Здесь то же самое дало бы строку на каждую
 * длительность аренды.</p>
 */
public final class ApiLog {

    private ApiLog() {}

    /** Что уже печатали за сессию: {@code modId + '\0' + формат}. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- события

    /**
     * Редкое событие — печатается всегда, без галочки отладки, но ровно один раз за сессию
     * на пару «мод + вид события».
     *
     * <p>Сюда идут: получение дескриптора, регистрация политики или слушателя, первое взятие
     * и снятие аренды подавления, конфликт двух политик.</p>
     */
    public static void event(String modId, String format, Object... args) {
        if (!firstTime(modId, format)) return;
        AeroCamSync.LOGGER.info("[AeroCamSync] * {}: " + format, prepend(modId, args));
    }

    /** То же, но предупреждением: длинная аренда, вызов не из того потока, no-op на сервере. */
    public static void warn(String modId, String format, Object... args) {
        if (!firstTime(modId, format)) return;
        AeroCamSync.LOGGER.warn("[AeroCamSync] * {}: " + format, prepend(modId, args));
    }

    /** Только под {@code DEBUG_MESSAGES} и тоже раз за сессию — решения политик и прочий шум. */
    public static void debug(String modId, String format, Object... args) {
        if (!debugEnabled()) return;
        if (!firstTime(modId, format)) return;
        AeroCamSync.LOGGER.info("[AeroCamSync] * {}: " + format, prepend(modId, args));
    }

    private static boolean firstTime(String modId, String format) {
        return SEEN.add(modId + '\0' + format);
    }

    private static Object[] prepend(String modId, Object[] args) {
        Object[] full = new Object[args.length + 1];
        full[0] = modId;
        System.arraycopy(args, 0, full, 1, args.length);
        return full;
    }

    /** Сброс на выходе из мира: следующая сессия должна снова увидеть первые строки. */
    public static void resetSession() {
        SEEN.clear();
        lastSummaryNs = System.nanoTime();
    }

    // ---------------------------------------------------------------- сводка

    private static final long SUMMARY_PERIOD_NS = 30_000_000_000L;

    /**
     * Как часто трогать часы. {@code state()} зовут тысячи раз за кадр, и {@code nanoTime()}
     * на каждом вызове стоит дороже самого счётчика — проверяем время раз в 1024 обращения.
     */
    private static final int CLOCK_CHECK_MASK = 0x3FF;

    private static int sinceClockCheck = 0;
    private static long lastSummaryNs = System.nanoTime();

    /**
     * Учёт одного обращения к API. Сами вызовы не логируются никогда — это горячий путь;
     * наружу они выходят только сводкой раз в тридцать секунд под галочкой отладки.
     *
     * <p>Счётчики не атомарные намеренно: это статистика, а не состояние, и лишний CAS на
     * горячем пути дороже потерянного инкремента.</p>
     */
    static void count(AcsHandleImpl handle, AcsHandleImpl.Call call) {
        handle.counters[call.ordinal()]++;

        if ((++sinceClockCheck & CLOCK_CHECK_MASK) != 0) return;
        if (!debugEnabled()) return;

        long now = System.nanoTime();
        if (now - lastSummaryNs < SUMMARY_PERIOD_NS) return;
        lastSummaryNs = now;

        for (AcsHandleImpl h : HandleRegistry.all()) h.logSummaryAndReset();
    }

    // ---------------------------------------------------------------- сторона

    /**
     * Галочка отладки лежит в КЛИЕНТСКОМ конфиге, а этот класс общий. Ссылка на клиентский
     * пакет исполняется только под проверкой стороны — тот же приём, что в {@code TiltAccess}
     * и {@code SideGate}: на выделенном сервере класс {@code ClientTiltAccess} не грузится.
     */
    static boolean debugEnabled() {
        if (FMLEnvironment.dist != Dist.CLIENT) return false;
        return com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isDebugMessages();
    }
}
