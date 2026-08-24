package com.playsi.aero_cam_sync;

import com.playsi.aero_cam_sync.apiimpl.AimPolicies;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.EntityCollisionContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Сеть»: один перехват вместо точечного compat под каждый мод.
 *
 * <h2>Идея</h2>
 *
 * <p>Через {@code BlockGetter#clip} проходит подавляющее большинство лучей по блокам. Вместо
 * того чтобы искать каждый мод, который строит прицельный луч по-своему, ловим их всех здесь
 * и сдвигаем начало луча в наклонённую камеру.</p>
 *
 * <h2>Фильтр — это вся суть</h2>
 *
 * <p>Пропускаем через поправку ТОЛЬКО луч, начало которого совпадает с ванильной точкой глаза
 * владельца. Работает это потому, что все известные способы собрать глаз вручную —
 * {@code position().add(0, getEyeHeight(), 0)} и подобные — дают бит-в-бит ту же точку, что
 * {@code getEyePosition()}.</p>
 *
 * <p>Три свойства, которые из этого следуют:</p>
 * <ul>
 *   <li><b>Двойного сдвига не бывает по построению.</b> Всё, что уже поправлено (воронка Sable,
 *       наши редиректы), начинается в «глаз + дельта» — под фильтр не попадает;</li>
 *   <li><b>чужая физика не проходит</b> — её лучи не стартуют ровно в глазу игрока
 *       (именно на этом горел старый {@code ClipShifter} с порогом «ближе 4 блоков», Issue #30);</li>
 *   <li><b>владелец известен из самого контекста</b> — {@code ClipContext.collisionContext},
 *       гадать «чей это луч» не нужно, и это же даёт работу на обеих сторонах.</li>
 * </ul>
 *
 * <h2>Чего сеть заведомо не ловит</h2>
 *
 * <p>Свой обход блоков мимо {@code clip} (у Create это {@code RaycastHelper#rayTraceUntil},
 * через него идёт {@code BigOutlines}), точки отсчёта без луча (фокус посоха Simulated),
 * чужие метрики расстояния (Cut Through). Для них compat остаётся точечным.</p>
 *
 * <h2>Дверь наружу</h2>
 *
 * <p>Фильтр «начало ровно в глазу» — правило по умолчанию, а не единственное: мод может
 * переопределить его для своих лучей через {@code AimPolicy} публичного API
 * ({@link com.playsi.aero_cam_sync.apiimpl.AimPolicies}). Опрос встроен ниже, в
 * {@code decideWithPolicies}, и на трёх инвариантах сети не сказывается.</p>
 */
public final class ClipNet {

    private ClipNet() {}

    /**
     * Квадрат допуска на совпадение с точкой глаза. Ожидается точное равенство, это страховка.
     * Живёт в {@link TiltAccess}, потому что тем же порогом публичное API определяет
     * «наклон применяется» — два определения обязаны быть одним числом.
     */
    private static final double EPSILON_SQR = TiltAccess.EPSILON_SQR;

    /** Реентранси: наш собственный повторный clip не должен попасть в сеть снова. */
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Подавление сети на время НАШИХ собственных клипов.
     *
     * <p>Проверочный ран 2026-08-06 показал, что сеть ловит сама себя: в уловах оказались
     * {@code Camera#applyTerrainTilt} (наш расчёт коллизии камеры — он клипает от глаза, чтобы
     * прижать камеру к стене) и {@code GameRenderer#vanillaPick} (наша диагностика, которая
     * СПЕЦИАЛЬНО строит ненаклонённый эталонный луч). Первое — петля обратной связи: коллизия
     * начинала считаться от уже сдвинутой точки, то есть от результата собственной работы.
     * Второе делало эталон в логе бесполезным.</p>
     *
     * <p>Счётчик, а не флаг: вызовы вложены (обход восьми углов камеры внутри общего блока).</p>
     */
    private static final ThreadLocal<int[]> SUPPRESSED = ThreadLocal.withInitial(() -> new int[1]);

    /** Наш собственный клип — сеть его не трогает. Обязательно в паре с {@link #resume()}. */
    public static void suppress() {
        SUPPRESSED.get()[0]++;
    }

    public static void resume() {
        int[] depth = SUPPRESSED.get();
        if (depth[0] > 0) depth[0]--;
    }

    /** Что уже попало в сеть за сессию — чтобы не спамить одинаковыми строками. */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    /**
     * @return сдвинутый результат клипа, либо {@code null} — луч не прицельный, не трогаем.
     */
    public static BlockHitResult tryShift(BlockGetter blockGetter, ClipContext context) {
        if (INSIDE.get()) return null;
        if (SUPPRESSED.get()[0] > 0) return null;
        if (!(blockGetter instanceof Level level)) return null;
        if (!SideGate.isOwnThread(level)) return null;

        // Владелец луча — из самого контекста. Только он определяет, чей это прицел.
        if (!(context.collisionContext instanceof EntityCollisionContext entityContext)) return null;
        if (!(entityContext.getEntity() instanceof Player player)) return null;
        if (player.level() != level) return null;

        Vec3 offset = TiltAccess.aimEyeOffset(player);
        if (offset == null) return null;

        // Поправка пренебрежимо мала — наклона фактически нет. Без этой проверки сеть ловила
        // ванильный Entity#pick в ровных кадрах: воронка там ничего не добавляет, origin
        // остаётся ванильным, фильтр совпадает — и мы вхолостую пересчитывали клип со сдвигом
        // на ноль (ран 2026-08-06 №3: `Entity#pick … | offset=0,000`).
        if (offset.lengthSqr() < EPSILON_SQR) return null;

        // ГЛАВНАЯ ПРОВЕРКА: луч начинается ровно в ванильном глазу этого игрока.
        Vec3 from = context.getFrom();
        boolean startsAtEye = from.distanceToSqr(player.getEyePosition()) <= EPSILON_SQR;

        if (!decideWithPolicies(player, context, from, startsAtEye)) return null;

        ClipContext shifted = new ClipContext(
                from.add(offset),
                context.getTo().add(offset),
                context.block,
                context.fluid,
                player);

        INSIDE.set(Boolean.TRUE);
        try {
            BlockHitResult result = level.clip(shifted);
            // Ключ уникальности — только цепочка вызова. Величина поправки печатается, но в
            // ключ не входит: в ране №3 она попала туда по недосмотру, и Cut Through дал три
            // десятка строк — по одной на каждое новое значение offset.
            SideGate.reportCatch(level, ClipNet::describeCaller, offset.length());
            return result;
        } finally {
            INSIDE.set(Boolean.FALSE);
        }
    }

    /**
     * Сдвигать ли этот луч: штатное правило плюс политики публичного API.
     *
     * <p>Политики опрашиваются ПЕРЕД тем, как штатное правило станет окончательным, и потому
     * могут решить в обе стороны: {@code SHIFT} чинит прицельный луч, начинающийся не в глазу
     * (свой обход блоков мимо {@code clip} — случай {@code BigOutlines}), {@code KEEP_VANILLA}
     * убирает руки от чужой физики, которая случайно стартовала ровно в глазу (Issue #30).</p>
     *
     * <p>Три инварианта сети политики не трогают (§1.8): подавление наших собственных клипов,
     * общий гейт с воронкой и порог на пренебрежимо малую поправку остались ВЫШЕ по коду и
     * отсекают луч до любого опроса. Политика не может включить сдвиг там, где сдвигать нечем.</p>
     *
     * <p>Обычный случай — политик нет — это одно сравнение и ни одной аллокации.</p>
     */
    private static boolean decideWithPolicies(Player player, ClipContext context,
                                              Vec3 from, boolean startsAtEye) {
        if (AimPolicies.isEmpty()) return startsAtEye;

        return switch (AimPolicies.decide(player, from, context.getTo(), context, startsAtEye)) {
            case SHIFT -> true;
            case KEEP_VANILLA -> false;
            case PASS -> startsAtEye;
        };
    }

    /**
     * Кто именно попался в сеть — первый кадр стека вне ванильного {@code clip} и вне нас.
     *
     * <p>Ради этого эксперимент и затевался: заранее известно, какие пути ДОЛЖНЫ попасть, но не
     * известно, какие попадут на самом деле и не окажется ли среди них лишних.</p>
     */
    private static String describeCaller() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .map(f -> f.getClassName() + "#" + f.getMethodName())
                        .filter(name -> !name.startsWith("com.playsi.aero_cam_sync")
                                && !name.startsWith("net.minecraft.world.level.BlockGetter")
                                && !name.startsWith("java."))
                        // Трёх кадров хватает, чтобы отличить «мод дёрнул Entity#pick» от
                        // «ванильный пик внутри нашего окна»: по одному верхнему кадру
                        // Entity#pick прогона №2 отличить было нельзя.
                        .limit(3)
                        .reduce((a, b) -> a + " ← " + b)
                        .orElse("<unknown>"));
    }

    /** Уже видели такого вызывающего? Печатаем каждого ровно один раз за сессию. */
    public static boolean firstTimeSeen(String caller) {
        return SEEN.add(caller);
    }
}
