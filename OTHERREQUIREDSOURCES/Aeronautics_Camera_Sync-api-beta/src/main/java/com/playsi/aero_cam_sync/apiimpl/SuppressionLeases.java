package com.playsi.aero_cam_sync.apiimpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аренды подавления наклона: {@code modId -> время, до которого подавлено}.
 *
 * <h2>Почему аренда с владельцем, а не флаг</h2>
 *
 * <p>Без владельца мод Y, закончив свою катсцену, гасит подавление мода X, который свою ещё
 * не начал. Поэтому {@code suppress} ставит или продлевает СВОЮ аренду, {@code release} снимает
 * только свою, а подавление живо, пока жива хоть одна непросроченная.</p>
 *
 * <h2>Часы</h2>
 *
 * <p>Класс намеренно НЕ знает про Minecraft — это единственный кусок API, который реально
 * покрывается юнит-тестами, и ради этого он того стоит. Время подаётся снаружи через
 * {@link #advance(long, boolean)}: и реальные миллисекунды, и признак паузы. Внутри копится
 * собственное время, которое на паузе стоит.</p>
 *
 * <p>Пауза здесь не придирка: продлевать аренду мод будет из тика, в одиночке на паузе тики
 * стоят, а реальное время идёт — без оговорки катсцена отпустила бы наклон посреди паузы.</p>
 */
public final class SuppressionLeases {

    private SuppressionLeases() {}

    /** Аренда дольше этого печатает предупреждение. Запрещать не за что, но видно быть обязано. */
    static final long LONG_LEASE_MILLIS = 10_000L;

    /**
     * Потолок шага часов. Больше секунды за тик означает, что игра не шла вовсе (загрузка
     * мира, свёрнутое окно) — засчитывать это время аренде нечестно, она отпустила бы наклон
     * ровно в момент, когда игрок снова видит картинку.
     */
    private static final long MAX_STEP_MILLIS = 1_000L;

    private static final Map<String, Long> LEASES = new ConcurrentHashMap<>();

    /** Собственное время в миллисекундах: реальное минус то, что игра простояла на паузе. */
    private static volatile long now = 0L;

    /** Реальное время прошлого шага, {@code -1} — шагов ещё не было. */
    private static long lastRealMillis = -1L;

    /** Кэш «подавлено ли» — чтобы событие слушателям уходило только на переходе. */
    private static volatile boolean suppressed = false;

    // -------------------------------------------------------------------- API

    /** Поставить или продлить аренду мода. {@code millis <= 0} игнорируется. */
    public static void suppress(String modId, long millis) {
        if (millis <= 0) return;
        LEASES.put(modId, now + millis);
        refresh();
    }

    /** Снять аренду мода. Чужие не трогает — в этом весь смысл владельца. */
    public static void release(String modId) {
        if (LEASES.remove(modId) == null) return;
        refresh();
    }

    /** Подавлен ли наклон кем угодно. */
    public static boolean isSuppressed() {
        return suppressed;
    }

    /** Держит ли аренду именно этот мод. */
    public static boolean isSuppressedBy(String modId) {
        Long until = LEASES.get(modId);
        return until != null && until > now;
    }

    /** Держатели аренды. Порядок не гарантирован — так и записано в контракте. */
    public static List<String> holders() {
        if (LEASES.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>(LEASES.size());
        for (Map.Entry<String, Long> entry : LEASES.entrySet()) {
            if (entry.getValue() > now) result.add(entry.getKey());
        }
        return result;
    }

    // ----------------------------------------------------------------- часы

    /**
     * Шаг часов. Зовётся из клиентского тика; на паузе время не идёт.
     *
     * @param realMillis реальное время, монотонность не требуется — шаг назад просто пропускается
     * @param paused     стоит ли игра на паузе прямо сейчас
     */
    public static void advance(long realMillis, boolean paused) {
        long previous = lastRealMillis;
        lastRealMillis = realMillis;

        // Первый шаг только задаёт точку отсчёта: дельты ещё нет.
        if (previous < 0) return;
        if (paused) return;

        long delta = realMillis - previous;
        if (delta <= 0) return;
        if (delta > MAX_STEP_MILLIS) delta = MAX_STEP_MILLIS;

        now += delta;
        refresh();
    }

    /**
     * Сброс при выходе из мира: протухшая аренда не должна пережить сессию.
     *
     * <p>Часы тоже сбрасываются — иначе следующая сессия начнётся с накопленным временем, и
     * первая же аренда получила бы срок в прошлом.</p>
     */
    public static void reset() {
        boolean was = suppressed;
        LEASES.clear();
        now = 0L;
        lastRealMillis = -1L;
        suppressed = false;
        if (was) TiltListeners.suppressionChanged(false, Collections.emptyList());
    }

    // -------------------------------------------------------------- внутреннее

    /** Выкидывает просроченные и, если состояние сменилось, дёргает слушателей. */
    private static void refresh() {
        boolean any = false;
        for (Iterator<Map.Entry<String, Long>> it = LEASES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now) it.remove();
            else any = true;
        }

        if (any == suppressed) return;
        suppressed = any;
        // Прямой вызов, а не хук: TiltListeners тоже не тянет ни одного типа Minecraft,
        // поэтому юнит-тест этого класса от него не страдает.
        TiltListeners.suppressionChanged(any, holders());
    }

    /** Только для тестов: текущее собственное время. */
    static long currentTime() {
        return now;
    }
}
