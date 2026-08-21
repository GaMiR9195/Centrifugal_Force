package com.playsi.aero_cam_sync.client.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.playsi.aero_cam_sync.api.AcsClientState;
import com.playsi.aero_cam_sync.api.AcsHandle;
import com.playsi.aero_cam_sync.api.AcsRay;
import com.playsi.aero_cam_sync.api.AcsState;
import com.playsi.aero_cam_sync.api.AeroCamSyncApi;
import com.playsi.aero_cam_sync.api.AimPolicy;
import com.playsi.aero_cam_sync.api.TiltListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Тестовый потребитель публичного API. <b>Только дев-ран</b> — в продакшене не регистрируется
 * вовсе — гейт {@code DEV_API_TEST} в {@code AeroCamSyncClient}, см. ниже.
 *
 * <h2>Зачем он есть</h2>
 *
 * <p>Единственный способ проверить API целиком: что дескриптор вообще получается снаружи, что
 * строки со звёздочкой появляются в логе, что политики спорят так, как написано, и что
 * {@code withVanillaEye} действительно отключает сеть, а не только по нашим словам.</p>
 *
 * <p>Вторая работа важнее первой: <b>этот код дословно уезжает в документацию</b>. Пример,
 * который компилируется и запускается, не расходится с реальностью; разошедшийся пример хуже
 * отсутствующего. Поэтому он написан так, как писал бы сторонний мод, и не лезет ни в один наш
 * внутренний класс — только {@code com.playsi.aero_cam_sync.api}.</p>
 *
 * <h2>Чего он НЕ проверяет</h2>
 *
 * <p>Путь мягкой зависимости: {@code ModList.isLoaded}, обращение из отдельного класса,
 * поведение без ACS на classpath. Отсюда его не проверить в принципе — мы и есть ACS. Это
 * закрывается вручную, отдельным пунктом чек-листа при написании документации.</p>
 *
 * <h2>Проверка «нет в релизе» — снаружи, а не здесь</h2>
 *
 * <p>Гейт {@code !FMLLoader.isProduction()} стоит в {@code AeroCamSyncClient}, а не методом
 * этого класса, и это не вкусовщина: конструктор {@code KeyMapping} прописывает себя в
 * статические реестры Minecraft, то есть простая загрузка класса уже оставила бы в собранном
 * моде четыре чужие клавиши. Так класс в продакшене не грузится вовсе.</p>
 *
 * <h2>Клавиши</h2>
 *
 * <ul>
 *   <li>{@code [} — подавить наклон на три секунды;</li>
 *   <li>{@code ]} — напечатать снимок состояния в чат;</li>
 *   <li>{@code \} — клипнуть от глаза внутри и снаружи {@code withVanillaEye} и сравнить;</li>
 *   <li>{@code Backspace} — переключить режим политики прицела;</li>
 *   <li>{@code '} — пустить два пробных луча и напечатать, сдвинула ли их политика;</li>
 *   <li>{@code ;} — включить/выключить коллизию камеры через API;</li>
 *   <li>{@code .} — включить/выключить третье лицо через API.</li>
 * </ul>
 */
public final class AcsApiTestMod {

    private AcsApiTestMod() {}

    /** Так это выглядит у стороннего мода: один дескриптор на мод, можно держать в статике. */
    private static final String MOD_ID = "acs_test";

    private static AcsHandle acs;

    // ------------------------------------------------------------------- политика

    /** Что отвечать на СВОИ пробные лучи. Переключается клавишей — иначе спор политик не проверить. */
    private enum PolicyMode {
        /** Не наше дело. Так должен вести себя настоящий мод почти всегда. */
        PASS,
        /** «Это мой прицельный луч, хоть и начинается не в глазу». */
        SHIFT,
        /** «Это подвеска, не лезь» — случай Bits'n'Tracks, Issue #30. */
        KEEP_VANILLA,
        /** Две политики отвечают противоположное: в логе одна строка, побеждает первая. */
        CONFLICT
    }

    private static PolicyMode mode = PolicyMode.PASS;

    /**
     * Луч, про который политика сейчас отвечает. Опознание — по ССЫЛКЕ на {@code ClipContext},
     * который мы сами и создали.
     *
     * <p>Первая версия теста отвечала на каждый луч игрока подряд, и это оказалось бесполезно:
     * {@code SHIFT} двигал заодно чужую физику (ломались рельсы), {@code KEEP_VANILLA} глушил
     * всё прицеливание разом, а {@code CONFLICT} вырождался в {@code SHIFT}. Отличить «политика
     * работает» от «политика сломала игру» было нельзя. Проверка обязана трогать ровно два
     * луча — свои, — и ни одного чужого.</p>
     *
     * <p>Заодно это ровно тот совет, который уйдёт в документацию: политика отвечает
     * {@code PASS} на всё, что не является её собственным лучом.</p>
     */
    private static ClipContext probeContext = null;

    /**
     * Насколько проба «A» отступает от глаза, чтобы штатное правило сети её НЕ признало.
     * Допуск сети — 1e-4, так что сантиметр гарантированно вне его и при этом незаметен.
     */
    private static final double PROBE_MARK = 0.01;

    // ------------------------------------------------------------------- клавиши

    private static final KeyMapping SUPPRESS = devKey("suppress", GLFW.GLFW_KEY_LEFT_BRACKET);
    private static final KeyMapping SNAPSHOT = devKey("snapshot", GLFW.GLFW_KEY_RIGHT_BRACKET);
    private static final KeyMapping VANILLA_EYE = devKey("vanilla_eye", GLFW.GLFW_KEY_BACKSLASH);
    private static final KeyMapping POLICY = devKey("policy", GLFW.GLFW_KEY_BACKSPACE);
    private static final KeyMapping PROBE = devKey("probe", GLFW.GLFW_KEY_APOSTROPHE);
    private static final KeyMapping COLLISION = devKey("collision", GLFW.GLFW_KEY_SEMICOLON);
    private static final KeyMapping THIRD_PERSON = devKey("third_person", GLFW.GLFW_KEY_PERIOD);

    private static KeyMapping devKey(String name, int code) {
        return new KeyMapping("key.aero_cam_sync.devtest." + name,
                InputConstants.Type.KEYSYM, code, "key.category.aero_cam_sync");
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SUPPRESS);
        event.register(SNAPSHOT);
        event.register(VANILLA_EYE);
        event.register(POLICY);
        event.register(PROBE);
        event.register(COLLISION);
        event.register(THIRD_PERSON);
    }

    // ---------------------------------------------------------------------- init

    public static void init() {
        acs = AeroCamSyncApi.forMod(MOD_ID);

        acs.addListener(new TiltListener() {
            @Override public void onTiltStart(AcsState state) { chat("tilt start"); }
            @Override public void onTiltStop(AcsState state) { chat("tilt stop"); }
            @Override public void onSuppressionChanged(boolean suppressed, List<String> by) {
                chat("suppression " + (suppressed ? "on by " + by : "off"));
            }
        });

        // Две политики: вторая нужна только чтобы было с кем спорить в режиме CONFLICT.
        // Обе первым делом отсеивают чужие лучи — это и есть правило для настоящих модов.
        acs.addPolicy(query -> {
            if (query.context() != probeContext) return AimPolicy.Decision.PASS;
            return switch (mode) {
                case SHIFT, CONFLICT -> AimPolicy.Decision.SHIFT;
                case KEEP_VANILLA -> AimPolicy.Decision.KEEP_VANILLA;
                case PASS -> AimPolicy.Decision.PASS;
            };
        });
        acs.addPolicy(query -> query.context() == probeContext && mode == PolicyMode.CONFLICT
                ? AimPolicy.Decision.KEEP_VANILLA
                : AimPolicy.Decision.PASS);
    }

    // ---------------------------------------------------------------------- tick

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        while (SUPPRESS.consumeClick()) {
            acs.suppress(3_000);
            chat("suppress(3000), mine=" + acs.isSuppressedByMe());
        }

        while (SNAPSHOT.consumeClick()) {
            printSnapshot(player);
        }

        while (VANILLA_EYE.consumeClick()) {
            compareVanillaEyeScope(player);
        }

        while (POLICY.consumeClick()) {
            PolicyMode[] all = PolicyMode.values();
            mode = all[(mode.ordinal() + 1) % all.length];
            chat("aim policy mode -> " + mode);
        }

        while (PROBE.consumeClick()) {
            fireProbes(player);
        }

        while (COLLISION.consumeClick()) {
            toggleCameraCollision();
        }

        while (THIRD_PERSON.consumeClick()) {
            toggleThirdPerson();
        }
    }

    // ------------------------------------------------------------ третье лицо

    /**
     * Настоящий потребитель звал бы это один раз на старте — его сценарий либо живёт в третьем
     * лице, либо нет. Здесь клавишей, потому что иначе не проверить оба состояния подряд, не
     * перезапуская игру.
     */
    private static void toggleThirdPerson() {
        if (acs.isThirdPersonEnabledByMe()) {
            acs.disableThirdPerson();
            chat("third person OFF, enabled by anyone = " + acs.isThirdPersonEnabled());
        } else {
            acs.enableThirdPerson("test: scenario lives in third person");
            chat("third person ON, mine=" + acs.isThirdPersonEnabledByMe());
        }
    }

    // --------------------------------------------------------- коллизия камеры

    /**
     * Так это и делает настоящий потребитель — мод, который по-настоящему поворачивает игрока
     * и держит свой хитбокс. Здесь клавишей только потому, что иначе сценарий «наклон у стены
     * больше не гаснет» никак не проверить живьём.
     *
     * <p>Заодно проверяется владение: {@code isCameraCollisionDisabledByMe()} обязан отличаться
     * от {@code isCameraCollisionDisabled()}, если выключатель держит кто-то ещё.</p>
     */
    private static void toggleCameraCollision() {
        if (acs.isCameraCollisionDisabledByMe()) {
            acs.enableCameraCollision();
            chat("camera collision ON, disabled by anyone = " + acs.isCameraCollisionDisabled());
        } else {
            acs.disableCameraCollision("test: pretending to rotate the player for real");
            chat("camera collision OFF, mine=" + acs.isCameraCollisionDisabledByMe());
        }
    }

    // ------------------------------------------------------------------ политика

    /**
     * Два пробных луча — по одному на каждое решение политики.
     *
     * <p>«Сдвинули или нет» определяется сравнением с эталоном, который считается внутри
     * {@code withVanillaEye}: там сеть выключена гарантированно (это проверяется отдельно,
     * клавишей {@code \}), поэтому эталон честно ванильный и до политик дело не доходит.</p>
     *
     * <p>Что обязано получиться:</p>
     * <pre>
     * режим          A (не от глаза)   B (ровно от глаза)
     * PASS           false             true
     * SHIFT          true              true
     * KEEP_VANILLA   false             false
     * CONFLICT       true              true   + одна строка о споре в логе
     * </pre>
     */
    private static void fireProbes(LocalPlayer player) {
        double reach = player.blockInteractionRange();
        Vec3 dir = player.getViewVector(1.0f);
        Vec3 eye = player.getEyePosition(1.0f);

        chat("probes in mode " + mode + ":");
        probe(player, "A eye+" + PROBE_MARK + " (не от глаза)", eye.add(0, PROBE_MARK, 0), dir, reach);
        probe(player, "B eye            (ровно от глаза)", eye, dir, reach);
    }

    private static void probe(LocalPlayer player, String label, Vec3 from, Vec3 dir, double reach) {
        Vec3 to = from.add(dir.scale(reach));

        BlockHitResult reference = acs.withVanillaEye(() -> clipProbe(player, from, to));
        BlockHitResult actual = clipProbe(player, from, to);

        boolean shifted = reference.getLocation().distanceToSqr(actual.getLocation()) > 1.0e-8;
        chat("  " + label + " shifted=" + shifted + " " + hit(actual));
    }

    /** Клип, про который политика знает, что он наш: ссылка на контекст — и есть опознание. */
    private static BlockHitResult clipProbe(LocalPlayer player, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        probeContext = context;
        try {
            return player.level().clip(context);
        } finally {
            probeContext = null;
        }
    }

    // ------------------------------------------------------------------- снимок

    /**
     * Так снимок и полагается брать: один раз, потом читать. Все пары {@code vanilla*}/{@code aim*}
     * обязаны совпадать везде, кроме случая «стоим на наклонённой палубе в первом лице».
     */
    private static void printSnapshot(LocalPlayer player) {
        AcsState state = acs.state(player, 1.0f);

        chat("enabled=" + state.modEnabled()
                + " tilt=" + state.tiltApplied()
                + " suppressed=" + state.suppressed() + state.suppressedBy());

        chat("eye  " + vec(state.vanillaEye()) + " -> " + vec(state.aimEye()));
        chat("look " + vec(state.vanillaLook(1.0f)) + " -> " + vec(state.aimLook(1.0f)));

        AcsRay vanilla = state.vanillaRay(player.blockInteractionRange());
        AcsRay aim = state.aimRay(player.blockInteractionRange());
        chat("ray end " + vec(vanilla.to()) + " -> " + vec(aim.to()));

        AcsClientState client = state.client();
        if (client == null) {
            chat("client half: null (server side)");
            return;
        }
        chat("cam  " + vec(client.vanillaCameraPos()) + " -> " + vec(client.cameraPos()));
        chat("scale=" + String.format("%.3f", client.tiltScale())
                + " firstPerson=" + client.firstPerson()
                + " legacy=" + client.legacyPick()
                + " sub=" + (client.tiltSubLevel() != null)
                // Держатели выключателя коллизии — то же, что уходит в лог при регистрации,
                // но живьём: «почему камера лезет в блоки» отвечается не выходя из игры.
                + " collisionOff=" + client.cameraCollisionDisabled()
                + client.cameraCollisionDisabledBy()
                + " thirdPerson=" + client.thirdPersonEnabled()
                + client.thirdPersonEnabledBy());
    }

    /**
     * Проверка, что {@code withVanillaEye} действительно снимает поправку, а не только по нашим
     * словам: один и тот же луч внутри области и снаружи обязан дать РАЗНЫЕ точки попадания,
     * когда камера наклонена, и одинаковые, когда нет.
     */
    private static void compareVanillaEyeScope(LocalPlayer player) {
        Level level = player.level();
        Vec3 from = player.getEyePosition(1.0f);
        Vec3 to = from.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));

        BlockHitResult outside = clip(level, player, from, to);
        BlockHitResult inside = acs.withVanillaEye(() -> clip(level, player, from, to));

        chat("clip outside scope: " + hit(outside));
        chat("clip inside  scope: " + hit(inside));
        chat("delta = " + String.format("%.4f",
                outside.getLocation().distanceTo(inside.getLocation())));
    }

    private static BlockHitResult clip(Level level, LocalPlayer player, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    // ------------------------------------------------------------------ мелочи

    private static String hit(BlockHitResult result) {
        return result.getType() == HitResult.Type.MISS
                ? "MISS " + vec(result.getLocation())
                : result.getBlockPos().toShortString() + " " + vec(result.getLocation());
    }

    private static String vec(Vec3 v) {
        return String.format("(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }

    private static void chat(String message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        player.displayClientMessage(Component.literal("[acs_test] " + message), false);
    }
}
