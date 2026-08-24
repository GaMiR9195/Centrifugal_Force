package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.AcsState;
import net.minecraft.world.entity.player.Player;

/**
 * Переходы наклона: старт и конец. События подавления приходят не отсюда, а из
 * {@link SuppressionLeases} — там переход виден точнее, включая истечение аренды.
 *
 * <h2>Почему из тика, а не из кадра</h2>
 *
 * <p>Кадровая точка ({@code Camera#setup}) выходит из-под собственных гвардов: при выключенном
 * моде или в третьем лице {@code CameraMixin} выходит рано, и «наклон кончился» не пришло бы
 * никогда — а это ровно тот случай, о котором мод обязан узнать. Тик идёт безусловно.</p>
 *
 * <p>Двадцати опросов в секунду хватает: событие и так приходит с задержкой относительно
 * причины — конец наклона наступает, когда остаточный наклон уходит ниже порога, то есть
 * через несколько кадров после схода с палубы.</p>
 */
public final class TiltEvents {

    private TiltEvents() {}

    private static boolean tiltWasApplied = false;

    /** Клиентский тик. Пока никто не подписан, снимок даже не собирается — он стоит аллокации. */
    public static void tick(Player player) {
        if (TiltListeners.isEmpty()) {
            tiltWasApplied = false;
            return;
        }

        AcsState state = AcsStateImpl.capture(null, player, 1.0f);
        boolean applied = state.tiltApplied();
        if (applied == tiltWasApplied) return;

        tiltWasApplied = applied;
        if (applied) TiltListeners.tiltStarted(state);
        else TiltListeners.tiltStopped(state);
    }

    /** Выход из мира: следующий заход обязан начаться с чистого перехода. */
    public static void reset() {
        tiltWasApplied = false;
    }
}
