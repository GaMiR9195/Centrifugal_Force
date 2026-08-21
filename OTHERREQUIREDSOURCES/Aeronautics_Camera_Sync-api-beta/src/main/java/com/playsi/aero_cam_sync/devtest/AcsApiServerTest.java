package com.playsi.aero_cam_sync.devtest;

import com.playsi.aero_cam_sync.api.AcsHandle;
import com.playsi.aero_cam_sync.api.AcsRay;
import com.playsi.aero_cam_sync.api.AcsState;
import com.playsi.aero_cam_sync.api.AeroCamSyncApi;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Серверная половина тестового потребителя API. <b>Только дев-ран.</b>
 *
 * <p>Клиентский {@code AcsApiTestMod} до серверной стороны не дотягивается по построению, а
 * непроверенной она остаться не может: именно там ломается граница (Issue #33), и именно там
 * контракт отличается — {@code client()} обязан быть {@code null}, а {@code suppress()} —
 * пустышкой с предупреждением.</p>
 *
 * <p>Команда {@code /acsapi} (уровень оператора). Сама по себе она и есть проверка: если API
 * утянуло клиентский тип, до вывода дело не дойдёт вовсе.</p>
 *
 * <p><b>Одиночка — не выделенный сервер.</b> В одиночке сторона процесса — клиентская, поэтому
 * {@code suppress()} там сработает по-настоящему. Пустышкой он обязан быть только на выделенном
 * сервере, и проверять этот пункт нужно именно там.</p>
 *
 * <p>Гейт {@code !FMLLoader.isProduction()} стоит в {@code AeroCamSync}, а не здесь: так класс
 * в собранном моде не грузится вовсе и команда не появляется.</p>
 */
public final class AcsApiServerTest {

    private AcsApiServerTest() {}

    private static AcsHandle acs;

    /** Зовётся из конструктора мода под гейтом дев-рана. */
    public static void register() {
        acs = AeroCamSyncApi.forMod("acs_test");
        NeoForge.EVENT_BUS.register(AcsApiServerTest.class);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("acsapi")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            report(context.getSource().getPlayerOrException());
                            return 1;
                        }));
    }

    private static void report(ServerPlayer player) {
        AcsState state = acs.state(player, 1.0f);

        say(player, "enabled=" + state.modEnabled()
                + " tilt=" + state.tiltApplied()
                + " suppressed=" + state.suppressed() + state.suppressedBy());
        say(player, "eye  " + vec(state.vanillaEye()) + " -> " + vec(state.aimEye()));
        say(player, "look " + vec(state.vanillaLook(1.0f)) + " -> " + vec(state.aimLook(1.0f)));

        AcsRay aim = state.aimRay(player.blockInteractionRange());
        say(player, "aim ray end " + vec(aim.to()));

        // Главный серверный пункт контракта: клиентской половины на этой стороне нет.
        say(player, "client() = " + (state.client() == null ? "null (верно для сервера)"
                : "НЕ null — граница протекла!"));

        // Второй: на выделенном сервере аренда не берётся, в логе одно предупреждение.
        boolean before = acs.isSuppressed();
        acs.suppress(1_000);
        boolean after = acs.isSuppressed();
        acs.release();
        say(player, "suppress(): было=" + before + " стало=" + after
                + (after ? " (сработало — значит это одиночка, не выделенный сервер)"
                         : " (no-op, как и положено серверу)"));
    }

    private static void say(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[acs_test/server] " + message));
    }

    private static String vec(Vec3 v) {
        return String.format("(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }
}
