package com.playsi.aero_cam_sync.client.devtest;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.playsi.aero_cam_sync.api.AcsClientState;
import com.playsi.aero_cam_sync.api.AcsHandle;
import com.playsi.aero_cam_sync.api.AcsState;
import com.playsi.aero_cam_sync.api.AeroCamSyncApi;
import com.playsi.aero_cam_sync.api.TiltContext;
import com.playsi.aero_cam_sync.api.TiltSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;

import javax.annotation.Nullable;

/**
 * Тестовый потребитель {@code TiltSource} — команда {@code /settilt}. <b>Только дев-ран.</b>
 *
 * <h2>Зачем отдельно от {@link AcsApiTestMod}</h2>
 *
 * <p>У источника три вещи, которые клавишей не проверить: наклон нужно задавать ЧИСЛОМ (иначе
 * «применилось или нет» отличается от «применилось не то» только на глаз), приоритет нужно
 * видеть на ДВУХ источниках сразу, а результат — сверять со снимком. Всё это команда, а не
 * клавиша. Дескриптор берётся тот же самый ({@code acs_test}) — {@code forMod} на одном id
 * отдаёт один объект.</p>
 *
 * <h2>Что проверяется</h2>
 *
 * <ul>
 *   <li>{@code /settilt 15 0} — низкий источник задаёт наклон 15° вокруг мировой оси X.
 *       Камера, перекрестие и снаряды обязаны уехать вместе, и на палубе, и на ровной земле:
 *       источнику палуба не нужна вовсе;</li>
 *   <li>{@code /settilt high 0 25} — высокий перебивает низкий, даже когда включены оба.
 *       Числа в {@code status} обязаны показать наклон высокого;</li>
 *   <li>{@code /settilt scale 0.5} — «модификатор»: источник берёт {@link TiltContext#acsTilt()}
 *       и урезает вдвое. Отдельного механизма модификаторов нет, и это проверка, что он и не
 *       нужен — на палубе наклон обязан стать ровно вполовину меньше;</li>
 *   <li>{@code /settilt off} и клавиша {@code [} ({@code suppress}) — наклон обязан УЕХАТЬ
 *       ПЛАВНО от того значения, которое держал источник, а не прыгнуть в ноль. Это и есть
 *       проверка, что подавление гасит источники, не спрашивая их;</li>
 *   <li>у стены наклон от источника обязан гаснуть так же, как наш: {@code tiltScale}
 *       применяется поверх чужого значения.</li>
 * </ul>
 *
 * <p>Гейт {@code !FMLLoader.isProduction()} стоит в {@code AeroCamSyncClient}, как и у
 * {@link AcsApiTestMod}: так класс в собранном моде не грузится и команды не существует.</p>
 */
public final class TiltSourceTest {

    private TiltSourceTest() {}

    private static final String MOD_ID = "acs_test";

    private static AcsHandle acs;

    private static final float DEG = (float) (Math.PI / 180.0);

    /** Что источник отвечает сейчас. Переключается командой. */
    private enum Mode {
        /** Не претендую — так обязан вести себя настоящий мод почти всегда. */
        OFF,
        /** Свой наклон, ни на что наш не глядя: сценарий «в моём измерении своя гравитация». */
        FIXED,
        /** Наш наклон, урезанный: сценарий «пусть качает, но вдвое слабее». */
        SCALE
    }

    /**
     * Один источник. Их два — иначе приоритет не проверить: нужен кадр, на который претендуют
     * ОБА, и видимая разница в том, чей наклон применился.
     *
     * <p>Поля {@code volatile}: пишет их поток команды, а читает рендер-поток внутри
     * {@code Camera#setup}.</p>
     */
    private static final class Probe implements TiltSource {

        private final String label;
        private volatile Mode mode = Mode.OFF;
        private volatile float degX = 0f;
        private volatile float degZ = 0f;
        private volatile float factor = 1f;

        private Probe(String label) {
            this.label = label;
        }

        @Override
        public boolean appliesTo(TiltContext context) {
            return mode != Mode.OFF;
        }

        @Override
        public Quaternionf tilt(TiltContext context) {
            return switch (mode) {
                // Вокруг МИРОВЫХ осей, а не «вперёд/вбок»: наклон применяется до ванильного
                // поворота камеры, поэтому результат не зависит от того, куда смотрит игрок,
                // и «уехало на 15 градусов» проверяется глазом, а не на ощупь.
                case FIXED -> new Quaternionf().rotateZ(degZ * DEG).rotateX(degX * DEG);
                // Ровно то, ради чего существует acsTilt(): взять готовое и вернуть своё.
                case SCALE -> new Quaternionf().slerp(context.acsTilt(), factor);
                case OFF -> null;
            };
        }

        private String describe() {
            return switch (mode) {
                case OFF -> label + "=off";
                case FIXED -> String.format("%s=fixed(x=%.1f z=%.1f)", label, degX, degZ);
                case SCALE -> String.format("%s=scale(%.2f)", label, factor);
            };
        }
    }

    private static final Probe LOW = new Probe("low");
    private static final Probe HIGH = new Probe("high");

    // ---------------------------------------------------------------------- init

    /** Зовётся из {@code AeroCamSyncClient.onClientSetup} под гейтом дев-рана. */
    public static void init() {
        acs = AeroCamSyncApi.forMod(MOD_ID);

        // Так это и делает настоящий мод: один раз на старте, с приоритетом, о котором он
        // договорился с остальными. Ноль — «мне всё равно, кто выше».
        acs.addTiltSource(0, LOW);
        acs.addTiltSource(100, HIGH);

        NeoForge.EVENT_BUS.register(TiltSourceTest.class);
    }

    // ------------------------------------------------------------------- команда

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // Клиентская, а не серверная: наклон считается на клиенте, и проверять его надо в том
        // числе на сервере без мода — там серверную команду зарегистрировать негде.
        event.getDispatcher().register(
                Commands.literal("settilt")
                        .then(named("high", HIGH))
                        .then(Commands.literal("scale")
                                .then(Commands.argument("factor", FloatArgumentType.floatArg(0f, 1f))
                                        .executes(context -> {
                                            LOW.factor = FloatArgumentType.getFloat(context, "factor");
                                            LOW.mode = Mode.SCALE;
                                            return report(context.getSource());
                                        })))
                        .then(Commands.literal("off").executes(context -> {
                            LOW.mode = Mode.OFF;
                            HIGH.mode = Mode.OFF;
                            return report(context.getSource());
                        }))
                        .then(Commands.literal("status")
                                .executes(context -> report(context.getSource())))
                        // Без литерала — низкий источник: самый частый ввод не должен требовать
                        // лишнего слова.
                        .then(degrees(LOW)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> named(String name, Probe probe) {
        return Commands.literal(name).then(degrees(probe));
    }

    /** {@code <degX> <degZ>} — наклон в градусах вокруг мировых осей X и Z. */
    private static RequiredArgumentBuilder<CommandSourceStack, Float> degrees(Probe probe) {
        return Commands.argument("degX", FloatArgumentType.floatArg(-90f, 90f))
                .then(Commands.argument("degZ", FloatArgumentType.floatArg(-90f, 90f))
                        .executes(context -> {
                            probe.degX = FloatArgumentType.getFloat(context, "degX");
                            probe.degZ = FloatArgumentType.getFloat(context, "degZ");
                            probe.mode = Mode.FIXED;
                            return report(context.getSource());
                        }));
    }

    // -------------------------------------------------------------------- вывод

    /**
     * Что задано и что из этого вышло — в двух строках.
     *
     * <p>Результат читается СНИМКОМ, а не нашими полями: «кто забрал кадр» обязано отвечаться
     * тем же способом, каким это узнает сторонний мод, иначе проверка проверяет сама себя.</p>
     */
    private static int report(CommandSourceStack source) {
        say(source, LOW.describe() + " " + HIGH.describe());

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 1;

        AcsState state = acs.state(mc.player, 1.0f);
        AcsClientState client = state.client();

        say(source, "tiltSource=" + (client == null ? "?" : String.valueOf(client.tiltSource()))
                + " applied=" + state.tiltApplied()
                + " suppressed=" + state.suppressed() + state.suppressedBy()
                + (client == null ? "" : " scale=" + String.format("%.3f", client.tiltScale()))
                + " angle=" + String.format("%.1f", angleDegrees(client)));
        return 1;
    }

    /** Угол между ванильным и применённым поворотом камеры — «на сколько уехало» одним числом. */
    private static float angleDegrees(@Nullable AcsClientState client) {
        if (client == null) return 0f;

        Quaternionf delta = new Quaternionf(client.vanillaCameraRot()).conjugate()
                .mul(client.cameraRot()).normalize();
        return (float) Math.toDegrees(2.0 * Math.acos(Math.min(1.0, Math.abs(delta.w()))));
    }

    private static void say(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[acs_test/source] " + message), false);
    }
}
