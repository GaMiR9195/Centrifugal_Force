package com.playsi.aero_cam_sync.client.debug;

import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * ВРЕМЕННАЯ ДИАГНОСТИКА (2026-08-02). Результаты пика последнего кадра — чтобы клик мог
 * сверить отправленный хит с тем, что было посчитано в том же кадре (саблевел дрейфует,
 * межкадровое сравнение бессмысленно). Удалить вместе с Debug*-миксинами.
 *
 * <p>Отдельный класс, а не поля миксина: миксин не может содержать не-private static поля.</p>
 */
public final class PickDiagnostics {

    public static volatile String lastTilted = "-";
    public static volatile String lastVanilla = "-";

    /** Расхождение нового origin (глаз Sable + поправка) со старым (позиция камеры). */
    public static volatile String lastEyeDelta = "-";

    /**
     * Открылось ли окно пика в последнем кадре, и если нет — какой гейт его закрыл.
     * Кадр без окна = полностью ванильный пик, и клик в таком кадре ставит блок по
     * ненаклонённому лучу.
     */
    public static volatile String lastScope = "-";

    /** Результат ПОСЛЕДНЕГО вызова {@code pick(F)} и был ли он внутри окна. */
    public static volatile String lastPickTail = "-";

    /** Сколько раз вызвали {@code pick(F)} с прошлого клика — больше одного значит лишний пик. */
    public static volatile int picksThisFrame = 0;

    // ------------------------------------------------------------------ запись

    /**
     * ЕДИНСТВЕННОЕ место, где решается, ведём ли мы диагностику вообще.
     *
     * <p>Раньше её решали вызывающие — и не решали: {@code PickScopeMixin} и
     * {@code SableEyeMixin} НЕ отладочные, они применяются и в релизе, а писали сюда безусловно.
     * Дороже всего обходилась {@link #recordEyeDelta}: {@code String.format} на каждой подмене
     * точки глаза, то есть на каждом луче «от глаза» за кадр.</p>
     *
     * <p>Поэтому гейт стоит здесь, а не у вызывающих: снаружи говорят «запиши», а решает класс.
     * Иначе следующий вызывающий снова забудет спросить.</p>
     */
    public static boolean enabled() {
        return com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isDebugMessages();
    }

    /** Открылось окно пика или нет; строка уходит в отладочную сводку клика. */
    public static void recordScope(String scope) {
        if (!enabled()) return;
        lastScope = scope;
    }

    /** Окно не открылось, и вот какой гейт его закрыл. Конкатенация — под гейтом намеренно. */
    public static void recordScopeDenied(String reason) {
        if (!enabled()) return;
        lastScope = "off:" + reason;
    }

    /** Начало нового пика: счётчик лишних вызовов {@code pick(F)} обнуляется. */
    public static void resetPickCount() {
        if (!enabled()) return;
        picksThisFrame = 0;
    }

    /**
     * Расхождение нового origin со старым. {@code from} может быть {@code null} — окно закрыто,
     * сравнивать не с чем.
     */
    public static void recordEyeDelta(@Nullable Vec3 from, Vec3 to) {
        if (from == null || !enabled()) return;
        lastEyeDelta = String.format("%.3f", from.distanceTo(to));
    }

    /**
     * Печатает каждого уникального вызывающего ровно один раз. Выборка по таймеру давала
     * неполную картину: при двух вызовах за кадр каждый N-й попадал всегда в одну и ту же
     * позицию, и второй вызывающий в лог не попадал никогда.
     */
    public static void logOnce(String tag, String caller) {
        if (seen.add(tag + "|" + caller)) {
            com.playsi.aero_cam_sync.AeroCamSync.LOGGER.info("[AeroCamSync] {}: {}", tag, caller);
        }
    }

    private static final java.util.Set<String> seen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Первые кадры стека без мишуры миксинов — по ним опознаётся мод. */
    public static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(f -> f.getClassName() + "#" + f.getMethodName())
                .filter(s -> !s.startsWith("java.lang.StackWalker"))
                .filter(s -> !s.contains("$aero_cam_sync$") && !s.contains("aero$"))
                .filter(s -> !s.contains("mixinextras$") && !s.contains("$mixinextras"))
                .limit(8)
                .reduce((a, b) -> a + "\n      <- " + b)
                .orElse("?"));
    }

    private PickDiagnostics() {
    }
}
