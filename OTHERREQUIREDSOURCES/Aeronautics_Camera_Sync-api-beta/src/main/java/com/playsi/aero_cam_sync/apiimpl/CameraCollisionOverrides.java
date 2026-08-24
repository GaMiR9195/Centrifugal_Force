package com.playsi.aero_cam_sync.apiimpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Выключатели коллизии камеры: {@code modId -> причина}.
 *
 * <h2>Почему владелец, а не флаг</h2>
 *
 * <p>Ровно та же причина, что у {@link SuppressionLeases}: без владельца второй мод снимает
 * выключатель первого. Поэтому {@code disable} ставит СВОЙ выключатель, {@code enable} снимает
 * только свой, а коллизии нет, пока держит хоть один.</p>
 *
 * <h2>Почему без часов и без аренд</h2>
 *
 * <p>Аренда отвечает на вопрос «сколько ещё», а здесь вопрос другой: мод утверждает СВОЙСТВО
 * СЕБЯ — «я по-настоящему поворачиваю игрока и держу свой хитбокс, ваша проверка меряет от
 * ванильной точки, которой в этот момент не существует». Такое утверждение не истекает.</p>
 *
 * <h2>⚠ Выход из мира НЕ сбрасывает</h2>
 *
 * <p>И это единственное осмысленное отличие от подавления. Регистрация — свойство мода, а не
 * свойство сессии: сбрасывай мы её, мод, зарегистрировавшийся один раз на старте, со второго
 * входа в мир молча остался бы без выключателя, а дефект вылез бы только после релоада.
 * Поэтому здесь нет {@code reset()} и его нет в {@code AeroCamSyncClient#onClientDisconnected}
 * рядом с {@code SuppressionLeases.reset()} — это намеренно, а не забыто.</p>
 *
 * <p>Регистрация идемпотентна по {@code modId}: повторный вызов только обновляет причину.</p>
 */
public final class CameraCollisionOverrides {

    private CameraCollisionOverrides() {}

    private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();

    /**
     * Кэш булева ответа — то же поле, что {@code suppressed} у аренд, и по той же причине:
     * спрашивают об этом из {@code updateWallScale}, то есть каждый кадр.
     */
    private static volatile boolean disabled = false;

    // -------------------------------------------------------------------- API

    /**
     * Поставить выключатель мода.
     *
     * @return {@code true}, если это ПЕРВАЯ регистрация этого мода — по ней решается, писать ли
     *         строку в лог: повторный вызов не событие
     */
    public static synchronized boolean disable(String modId, String reason) {
        boolean first = OVERRIDES.put(modId, reason) == null;
        disabled = true;
        return first;
    }

    /**
     * Снять выключатель мода. Чужие не трогает — в этом весь смысл владельца.
     *
     * @return {@code true}, если выключатель этого мода действительно стоял
     */
    public static synchronized boolean enable(String modId) {
        if (OVERRIDES.remove(modId) == null) return false;
        disabled = !OVERRIDES.isEmpty();
        return true;
    }

    /**
     * Выключена ли коллизия камеры кем угодно.
     *
     * <p>Мутаторы синхронизированы, чтение — нет: гонка двух модов на разных потоках иначе
     * оставила бы кэш рассогласованным с картой навсегда, а горячий путь платить за это не
     * должен.</p>
     */
    public static boolean isDisabled() {
        return disabled;
    }

    /** Держит ли выключатель именно этот мод. */
    public static boolean isDisabledBy(String modId) {
        return OVERRIDES.containsKey(modId);
    }

    /** Держатели выключателя. Порядок не гарантирован — так и записано в контракте. */
    public static List<String> holders() {
        if (OVERRIDES.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(OVERRIDES.keySet());
    }
}
