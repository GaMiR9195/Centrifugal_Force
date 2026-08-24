package com.playsi.aero_cam_sync.apiimpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Включатели третьего лица: {@code modId -> причина}.
 *
 * <p>Зеркало {@link CameraCollisionOverrides} с обратным умолчанием. Там мод ВЫКЛЮЧАЕТ нашу
 * проверку, здесь — ВКЛЮЧАЕТ наш режим: по умолчанию в третьем лице мод не делает ничего,
 * камера ванильная и прицел ванильный, пока кто-нибудь этого не попросил.</p>
 *
 * <h2>Почему один переключатель, а не два</h2>
 *
 * <p>Напрашивается разделение «наклонять камеру» и «пускать за ней прицел», но вторая половина
 * без первой бессмысленна по механике: {@code CameraController.shouldApplyTilt()} в третьем лице
 * возвращает {@code false}, {@code CameraMixin} выходит по гейту, и {@code updateSmoothedTilt}
 * там не вызывается вовсе. Прицел, сдвинутый без наклона, ехал бы на кватернионе, за которым
 * никто не следит. Поэтому переключатель один и означает третье лицо целиком: либо камера
 * повёрнута и все лучи следуют за ней, либо не происходит ни того ни другого. Комбинацию,
 * ради устранения которой всё и делалось (§1.6, гибридный луч), выразить нельзя.</p>
 *
 * <h2>Почему владелец, а не флаг</h2>
 *
 * <p>Та же причина, что у {@link SuppressionLeases} и у выключателя коллизии: без владельца
 * второй мод снимает то, что поставил первый. Поэтому {@code enable} ставит СВОЙ включатель,
 * {@code disable} снимает только свой, а третье лицо работает, пока держит хоть один.</p>
 *
 * <h2>Почему без часов и без аренд</h2>
 *
 * <p>Аренда отвечает на вопрос «сколько ещё», а здесь вопрос другой: мод утверждает СВОЙСТВО
 * СЕБЯ — «мой сценарий живёт в третьем лице, наклон там нужен». Такое утверждение не истекает.</p>
 *
 * <h2>⚠ Выход из мира НЕ сбрасывает</h2>
 *
 * <p>Как и у коллизии, и по той же причине: регистрация — свойство мода, а не свойство сессии.
 * Сбрасывай мы её, мод, зарегистрировавшийся один раз на старте, со второго входа в мир молча
 * остался бы без третьего лица. Поэтому здесь нет {@code reset()} и его нет в
 * {@code AeroCamSyncClient#onClientDisconnected} рядом с {@code SuppressionLeases.reset()} —
 * это намеренно, а не забыто.</p>
 *
 * <p>Регистрация идемпотентна по {@code modId}: повторный вызов только обновляет причину.</p>
 */
public final class ThirdPersonOverrides {

    private ThirdPersonOverrides() {}

    private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();

    /**
     * Кэш булева ответа — то же поле, что {@code disabled} у коллизии, и по той же причине:
     * спрашивают об этом с горячего пути, из общего гейта третьего лица, каждый кадр.
     */
    private static volatile boolean enabled = false;

    // -------------------------------------------------------------------- API

    /**
     * Поставить включатель мода.
     *
     * @return {@code true}, если это ПЕРВАЯ регистрация этого мода — по ней решается, писать ли
     *         строку в лог: повторный вызов не событие
     */
    public static synchronized boolean enable(String modId, String reason) {
        boolean first = OVERRIDES.put(modId, reason) == null;
        enabled = true;
        return first;
    }

    /**
     * Снять включатель мода. Чужие не трогает — в этом весь смысл владельца.
     *
     * @return {@code true}, если включатель этого мода действительно стоял
     */
    public static synchronized boolean disable(String modId) {
        if (OVERRIDES.remove(modId) == null) return false;
        enabled = !OVERRIDES.isEmpty();
        return true;
    }

    /**
     * Включено ли третье лицо кем угодно.
     *
     * <p>Мутаторы синхронизированы, чтение — нет: гонка двух модов на разных потоках иначе
     * оставила бы кэш рассогласованным с картой навсегда, а горячий путь платить за это не
     * должен.</p>
     *
     * <p><b>С горячего пути это читают не напрямую.</b> Значение обязано быть одинаковым во всех
     * точках прицела в пределах кадра, иначе флип посреди кадра даёт гибридный луч — снимок
     * берётся один раз за кадр, см. {@code CameraController.tickApplyState()}.</p>
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Держит ли включатель именно этот мод. */
    public static boolean isEnabledBy(String modId) {
        return OVERRIDES.containsKey(modId);
    }

    /** Держатели включателя. Порядок не гарантирован — так и записано в контракте. */
    public static List<String> holders() {
        if (OVERRIDES.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(OVERRIDES.keySet());
    }
}
