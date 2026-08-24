package com.playsi.aero_cam_sync.apiimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Аренды подавления — единственный кусок API с чистой логикой и без Minecraft, и
 * одновременно самый ошибкоопасный. Часы подаются снаружи, поэтому всё проверяется без игры.
 */
class SuppressionLeasesTest {

    private long realClock;

    @BeforeEach
    void setUp() {
        SuppressionLeases.reset();
        // Заведомо не ноль: класс не должен зависеть от того, с какого числа стартовали часы.
        realClock = 1_700_000_000_000L;
        SuppressionLeases.advance(realClock, false); // первый шаг только задаёт точку отсчёта
        assertEquals(0L, SuppressionLeases.currentTime());
    }

    /** Шагает мелко: разовый шаг больше секунды часы намеренно урезают. */
    private void elapse(long millis, boolean paused) {
        for (long left = millis; left > 0; ) {
            long step = Math.min(left, 500L);
            realClock += step;
            SuppressionLeases.advance(realClock, paused);
            left -= step;
        }
    }

    private void elapse(long millis) {
        elapse(millis, false);
    }

    @Test
    @DisplayName("аренда живёт ровно свой срок и истекает сама")
    void expires() {
        SuppressionLeases.suppress("a", 1_000);
        assertTrue(SuppressionLeases.isSuppressed());

        elapse(900);
        assertTrue(SuppressionLeases.isSuppressed(), "900 мс из 1000 — ещё живо");

        elapse(200);
        assertFalse(SuppressionLeases.isSuppressed(), "1100 мс из 1000 — истекло");
        assertTrue(SuppressionLeases.holders().isEmpty());
    }

    @Test
    @DisplayName("повторный suppress продлевает свою аренду, а не заводит вторую")
    void extends_() {
        SuppressionLeases.suppress("a", 1_000);
        elapse(800);
        SuppressionLeases.suppress("a", 1_000);

        elapse(800);
        assertTrue(SuppressionLeases.isSuppressed(), "продление отсчитывается от момента вызова");
        assertEquals(List.of("a"), SuppressionLeases.holders());

        elapse(400);
        assertFalse(SuppressionLeases.isSuppressed());
    }

    @Test
    @DisplayName("release чужим модом не снимает аренду — ради этого владелец и заводился")
    void foreignReleaseDoesNothing() {
        SuppressionLeases.suppress("a", 1_000);

        SuppressionLeases.release("b");
        assertTrue(SuppressionLeases.isSuppressed());
        assertTrue(SuppressionLeases.isSuppressedBy("a"));
        assertFalse(SuppressionLeases.isSuppressedBy("b"));

        SuppressionLeases.release("a");
        assertFalse(SuppressionLeases.isSuppressed());
    }

    @Test
    @DisplayName("подавление живо, пока жива хоть одна аренда")
    void survivesWhileAnyLeaseLives() {
        SuppressionLeases.suppress("a", 1_000);
        SuppressionLeases.suppress("b", 3_000);

        elapse(1_500);
        assertTrue(SuppressionLeases.isSuppressed());
        assertFalse(SuppressionLeases.isSuppressedBy("a"), "аренда a истекла");
        assertTrue(SuppressionLeases.isSuppressedBy("b"));
        assertEquals(List.of("b"), SuppressionLeases.holders());

        elapse(2_000);
        assertFalse(SuppressionLeases.isSuppressed());
    }

    @Test
    @DisplayName("на паузе часы стоят: катсцена не отпускает наклон в меню")
    void clockStopsWhilePaused() {
        SuppressionLeases.suppress("a", 1_000);

        elapse(5_000, true);
        assertTrue(SuppressionLeases.isSuppressed(), "пять секунд паузы аренде не засчитываются");
        assertEquals(0L, SuppressionLeases.currentTime());

        elapse(1_100, false);
        assertFalse(SuppressionLeases.isSuppressed());
    }

    @Test
    @DisplayName("простой игры дольше секунды урезается: аренда не сгорает за одну загрузку мира")
    void hugeStepIsClamped() {
        SuppressionLeases.suppress("a", 5_000);

        realClock += 60_000;
        SuppressionLeases.advance(realClock, false);

        assertTrue(SuppressionLeases.isSuppressed());
        assertEquals(1_000L, SuppressionLeases.currentTime(), "засчитан ровно потолок шага");
    }

    @Test
    @DisplayName("выход из мира снимает всё: протухшая аренда не переживает сессию")
    void resetDropsEverything() {
        SuppressionLeases.suppress("a", 60_000);
        assertTrue(SuppressionLeases.isSuppressed());

        SuppressionLeases.reset();

        assertFalse(SuppressionLeases.isSuppressed());
        assertFalse(SuppressionLeases.isSuppressedBy("a"));
        assertTrue(SuppressionLeases.holders().isEmpty());
        assertEquals(0L, SuppressionLeases.currentTime());
    }

    @Test
    @DisplayName("нулевая и отрицательная длительность игнорируются")
    void nonPositiveLeaseIgnored() {
        SuppressionLeases.suppress("a", 0);
        SuppressionLeases.suppress("a", -5);
        assertFalse(SuppressionLeases.isSuppressed());
    }
}
