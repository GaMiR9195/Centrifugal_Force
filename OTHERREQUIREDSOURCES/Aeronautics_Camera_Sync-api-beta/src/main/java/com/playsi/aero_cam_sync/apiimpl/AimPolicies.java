package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.AimPolicy;
import com.playsi.aero_cam_sync.api.AimQuery;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реестр политик прицела, опрашиваемый из {@code ClipNet}.
 *
 * <p><b>Цена.</b> {@code decide} зовётся из {@code clip} десятки раз за кадр. Обычный случай —
 * политик нет вовсе — стоит одну проверку {@link #isEmpty()} и ни одной аллокации; объект
 * {@link AimQuery} собирается только тогда, когда есть кому его читать.</p>
 */
public final class AimPolicies {

    private AimPolicies() {}

    private record Registered(String modId, AimPolicy policy) {}

    private static final CopyOnWriteArrayList<Registered> POLICIES = new CopyOnWriteArrayList<>();

    public static void add(String modId, AimPolicy policy) {
        POLICIES.add(new Registered(modId, policy));
    }

    public static boolean isEmpty() {
        return POLICIES.isEmpty();
    }

    /**
     * Спрашивает политики в порядке регистрации.
     *
     * <p>Побеждает первое не-{@code PASS}. Остальные всё равно опрашиваются: спор двух политик
     * на одном луче — чей-то баг, и он обязан быть виден в логе без галочки отладки. Политик
     * единицы, так что полный обход дешевле, чем молча отдать пользователю несогласованное
     * поведение.</p>
     *
     * <p>Факты принимаются россыпью, а объект {@link AimQuery} собирается здесь: вызывающий
     * ({@code ClipNet}) обязан иметь возможность отсеяться по {@link #isEmpty()} до любой
     * аллокации.</p>
     *
     * @return решение, либо {@link AimPolicy.Decision#PASS} — политики промолчали, работает
     *         штатное правило сети
     */
    public static AimPolicy.Decision decide(Player player, Vec3 from, Vec3 to,
                                            @Nullable ClipContext context, boolean startsAtEye) {
        AimQuery query = new AimQueryImpl(player, from, to, context, startsAtEye);

        AimPolicy.Decision winner = AimPolicy.Decision.PASS;
        String winnerMod = null;

        for (Registered r : POLICIES) {
            AimPolicy.Decision decision;
            try {
                decision = r.policy().decide(query);
            } catch (Throwable t) {
                ApiLog.warn(r.modId(), "aim policy threw, treated as PASS: {}", String.valueOf(t));
                continue;
            }
            if (decision == null || decision == AimPolicy.Decision.PASS) continue;

            if (winnerMod == null) {
                winner = decision;
                winnerMod = r.modId();
            } else if (decision != winner) {
                // Аргументы передаются россыпью, без склейки строк: спорящие политики отвечают
                // так на КАЖДЫЙ луч, а дедупликация отсекает вывод уже внутри ApiLog.
                ApiLog.event(winnerMod, "aim policy conflict with {}: {} vs {}, first wins",
                        r.modId(), winner, decision);
            }
        }

        // Под галочкой отладки — по строке на каждое новое решение каждого мода за сессию.
        // Строка собирается только когда галочка включена: это горячий путь.
        if (winnerMod != null && ApiLog.debugEnabled()) {
            ApiLog.debug(winnerMod, "aim policy decided " + winner);
        }

        return winner;
    }
}
