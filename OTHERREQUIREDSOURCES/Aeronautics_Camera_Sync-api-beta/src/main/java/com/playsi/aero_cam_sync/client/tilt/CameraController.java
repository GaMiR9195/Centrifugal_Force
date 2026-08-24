package com.playsi.aero_cam_sync.client.tilt;

import com.playsi.aero_cam_sync.ClipNet;
import com.playsi.aero_cam_sync.apiimpl.CameraCollisionOverrides;
import com.playsi.aero_cam_sync.apiimpl.SuppressionLeases;
import com.playsi.aero_cam_sync.apiimpl.ThirdPersonOverrides;
import com.playsi.aero_cam_sync.apiimpl.TiltSources;
import com.playsi.aero_cam_sync.client.camera.FrameVanillaState;
import com.playsi.aero_cam_sync.client.camera.LevelClipMixinState;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.sublevel.SubLevelTracker;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Управляет сглаженным тилтом и применяет его к ванильной камере.
 *
 * <p>Состояние ({@code smoothedTilt}, {@code wallScale} и прочее) хранится статически: в каждый
 * момент времени активный тилт у игрока один, потому что и камера одна.</p>
 *
 * <p><b>Чтение и запись разведены намеренно.</b> Читать может кто угодно и откуда угодно —
 * читателей много и они вне кадра камеры (общий {@code EntityLookMixin}, клиентский тик,
 * публичное API), передать им ссылку на объект невозможно. Писать можно только через
 * {@link Frame}, а его выдаёт {@link #forMainCamera(Camera)} и только главной камере: состояние
 * портила именно запись из вторичной (§2 подводных камней).</p>
 */
public final class CameraController {

    private CameraController() {}

    static final Quaternionf smoothedTilt = new Quaternionf();
    private static boolean wasApplyingTilt = false;

    // Множитель близости к стене: 1 = стен рядом нет (полный наклон), 0 = камера
    // вплотную к стене (наклона нет). Сглаживается, чтобы не дёргалось.
    private static float wallScale = 1.0f;

    /** Режим камеры прошлого кадра; {@code null} — ещё ни одного кадра не было. */
    private static Boolean lastFirstPerson = null;

    /** Режим камеры сменился — {@link #updateWallScale} обязан сбросить {@code wallScale} мгновенно. */
    private static boolean cameraModeChanged = false;

    /**
     * Снимок включателя третьего лица на текущий кадр.
     *
     * <p>Само значение живёт в {@link ThirdPersonOverrides} и может быть изменено чужим модом
     * из любого места. Спрашивают о нём пять точек прицела, и они обязаны получить ОДИН И ТОТ ЖЕ
     * ответ в пределах кадра: иначе флип посреди кадра даёт луч с наклонённым направлением и
     * ненаклонённым началом — тот самый гибрид (§1.6). Поэтому читаем один раз, в
     * {@link #applyTickState()}, а все потребители идут через {@link #isThirdPersonAllowed()}.</p>
     *
     * <p>{@code volatile}, потому что пишется в рендер-потоке, а читаться может и из клиентского
     * тика (синхронизация тилта на сервер).</p>
     */
    private static volatile boolean thirdPersonAllowed = false;

    /**
     * Мод, чей {@code TiltSource} задал наклон в ЭТОМ кадре, либо {@code null} — считали сами.
     *
     * <p>Значение кадра, а не реестр: источник, который в этом кадре не претендовал, здесь не
     * назван. Реестр держит {@link TiltSources}, а наружу это уходит через
     * {@code AcsClientState.tiltSource()} — по нему в чужом логе видно, кто забрал наклон.</p>
     *
     * <p>Сбрасывается в {@link #applyTickState()}, потому что он единственный зовётся
     * БЕЗУСЛОВНО: выйди {@code CameraMixin} раньше по своим гейтам (мод выключен, третье лицо),
     * и без сброса здесь навсегда остался бы висеть победитель последнего сработавшего кадра.</p>
     *
     * <p>{@code volatile} по той же причине, что и {@link #thirdPersonAllowed}: пишется в
     * рендер-потоке, читается в том числе из клиентского тика.</p>
     */
    @javax.annotation.Nullable
    private static volatile String tiltSourceMod = null;

    // -------------------------------------------------------------------------
    // Право на запись
    // -------------------------------------------------------------------------

    /**
     * Пишущая ссылка на состояние наклона. Получить её можно ТОЛЬКО для главной камеры —
     * см. {@link #forMainCamera(Camera)}.
     *
     * <p><b>Зачем тип вместо гварда.</b> Состояние наклона глобально: {@link #smoothedTilt},
     * {@link #wallScale} и всё остальное существует в единственном экземпляре, потому что и
     * камера одна. Но {@code Camera#setup} зовут и другие камеры — экран диаграммы Create
     * Simulated рендерит второй вид своей. Один раз она уже пересчитала общий {@code wallScale}
     * от своей позиции и устроила пилу на главном виде (§2 подводных камней). Починили это
     * ранним выходом в {@code CameraMixin}, и он работает — ровно до тех пор, пока следующий
     * автор о нём помнит.</p>
     *
     * <p>Теперь помнить не нужно: писать некуда, пока не спросил {@link #forMainCamera(Camera)},
     * а он вторичной камере отвечает {@code null}. Читать состояние по-прежнему может кто угодно
     * и статически — читателей много и они вне кадра (общий {@code EntityLookMixin}, клиентский
     * тик, публичное API), передать им ссылку невозможно, да и незачем: портила состояние
     * запись, а не чтение.</p>
     */
    public static final class Frame {

        private Frame() {}

        /** Ванильные позиция и поворот этого кадра — до любого нашего вмешательства. */
        public void captureVanilla(Camera camera) {
            FrameVanillaState.capture(camera);
        }

        /** См. {@link CameraController#applyTickState()}. */
        public void tickApplyState() {
            applyTickState();
        }

        /** См. {@link CameraController#slerpSmoothedTilt}. */
        public void updateSmoothedTilt(@javax.annotation.Nullable Vector3f surfaceNormal,
                                       float deltaTime, float partialTick, boolean freeze) {
            slerpSmoothedTilt(surfaceNormal, deltaTime, partialTick, freeze);
        }

        /** См. {@link CameraController#applyToCamera}. */
        public void applyTiltToCamera(Camera camera, float partialTick) {
            applyToCamera(camera, partialTick);
        }
    }

    private static final Frame FRAME = new Frame();

    /**
     * Пишущая ссылка, если это главная игровая камера, иначе {@code null}.
     *
     * <p>Вторичные камеры (экран диаграммы Create Simulated и любой чужой мод со своей камерой)
     * получают {@code null} и не могут тронуть состояние даже по ошибке.</p>
     */
    @javax.annotation.Nullable
    public static Frame forMainCamera(Camera camera) {
        return camera == Minecraft.getInstance().gameRenderer.getMainCamera() ? FRAME : null;
    }

    // -------------------------------------------------------------------------
    // Публичный API — только чтение
    // -------------------------------------------------------------------------

    /**
     * Возвращает {@code true} если тилт вообще должен применяться
     * (мод включён, игрок не в транспортном средстве, режим камеры подходит).
     */
    public static boolean shouldApplyTilt() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return false;

        if (!Config.MOD_ENABLED.get()) return false;

        if (!isThirdPersonAllowed()
                && !mc.options.getCameraType().isFirstPerson())
            return false;

        return player.getVehicle() == null;
    }

    /**
     * ЕДИНСТВЕННЫЙ гейт третьего лица. Спрашивать режим камеры напрямую нельзя — иначе точки
     * прицела снова разъедутся, как разъезжались {@code EntityLookMixin} и
     * {@code isAimShiftAllowed()} (долг 12.3).
     *
     * <p>Отдаёт снимок кадра, а не живое значение: см. {@link #thirdPersonAllowed}.</p>
     *
     * <p>Под аварийным откатом {@code LEGACY_PICK} всегда {@code false}: старый путь про третье
     * лицо не знает и переписывает пик по-своему, включать его туда мы не будем — так и записано
     * в контракте {@code AcsHandle.enableThirdPerson}.</p>
     */
    public static boolean isThirdPersonAllowed() {
        if (!thirdPersonAllowed) return false;
        return !ClientTiltAccess.isLegacyPick();
    }

    /**
     * Вызывать в начале каждого кадра ПЕРЕД updateSmoothedTilt.
     * Сбрасывает тилт если мод только что "включился" после паузы.
     *
     * <p>Здесь же отслеживается режим камеры — и отслеживать его больше негде: этот метод
     * единственный, кого {@code CameraMixin} зовёт БЕЗУСЛОВНО, до всех своих гвардов. Стой
     * отслеживание ниже, переход 3-е → 1-е при выключенном третьем лице пропадал бы: в третьем
     * лице наклон там не считается вовсе.</p>
     *
     * <p>И по той же причине здесь берётся снимок включателя третьего лица — раньше всего
     * остального в кадре, чтобы {@link #shouldApplyTilt()} и все точки прицела читали уже его,
     * а не живое значение.</p>
     */
    private static void applyTickState() {
        // ПЕРВОЙ строкой: ниже её результат читает shouldApplyTilt(), а за ним — весь кадр.
        thirdPersonAllowed = ThirdPersonOverrides.isEnabled();

        // Победитель прошлого кадра забывается ЗДЕСЬ, а не в applyTiltSource: тот стоит за
        // гейтами CameraMixin, и в кадре, где мод выключен или мы в третьем лице, до него дело
        // не дойдёт вовсе — имя чужого мода так и висело бы в снимке.
        tiltSourceMod = null;

        boolean applying = shouldApplyTilt();
        if (applying && !wasApplyingTilt) {
            smoothedTilt.identity();
        }
        wasApplyingTilt = applying;

        boolean firstPerson = Minecraft.getInstance().options.getCameraType().isFirstPerson();
        if (lastFirstPerson != null && lastFirstPerson.booleanValue() != firstPerson) {
            cameraModeChanged = true;
        }
        lastFirstPerson = firstPerson;
    }

    /**
     * Обновляет сглаженный тилт.
     *
     * <p>Последним шагом сюда же приходят чужие источники наклона — см.
     * {@link #applyTiltSource}. Точка одна и она здесь намеренно: ниже по кадру наклон читают
     * камера, перекрестие, все лучи, снаряды и {@code TiltSync}, отправляющий его на сервер, и
     * все они обязаны получить ОДНО значение. Резолви мы источники в момент применения к
     * камере, клиент рисовал бы один наклон, а сервер считал попадания по другому.</p>
     *
     * @param surfaceNormal целевая нормаль, или {@code null} — плавный возврат к identity
     * @param deltaTime     время кадра (тики)
     * @param partialTick   доля тика этого кадра — нужна только источникам
     * @param freeze        если {@code true} — тилт не меняется (игрок в воздухе над сабвелом)
     */
    private static void slerpSmoothedTilt(@javax.annotation.Nullable Vector3f surfaceNormal,
                                          float deltaTime,
                                          float partialTick,
                                          boolean freeze) {
        if (!freeze) {
            Quaternionf target = (surfaceNormal != null)
                    ? new Quaternionf().rotationTo(new Vector3f(0f, 1f, 0f), surfaceNormal)
                    : new Quaternionf();

            float alpha = Config.SMOOTH_SPEED.get().floatValue();
            float t = 1f - (float) Math.pow(0.5, deltaTime / alpha);
            smoothedTilt.slerp(target, t);
        }

        // ПОСЛЕ нашего сглаживания, а не вместо него: источнику отдаётся уже посчитанный
        // наклон (TiltContext.acsTilt()), и мод, который хочет лишь урезать его вдвое, пишет
        // одну строку вместо собственного рейкаста. Заморозка источников не касается — она
        // про наш сценарий, а не про чужой.
        applyTiltSource(surfaceNormal, deltaTime, partialTick);
    }

    /**
     * Отдаёт кадр чужому источнику, если такой нашёлся.
     *
     * <p>Результат кладётся прямо в {@link #smoothedTilt}, и это даёт плавность даром: когда
     * источник перестаёт претендовать, обычный slerp выше продолжает вести кватернион к нашей
     * цели ОТ ТОГО значения, на котором чужой мод остановился, — без рывка и без единого
     * специального случая. По той же причине подавление гасит источники, НЕ спрашивая их:
     * наклон остаётся последним чужим и обычным порядком уезжает к единице.</p>
     *
     * <p>Урезание у стены ({@code wallScale}) применяется ПОСЛЕ и к чужому наклону тоже —
     * см. {@link #effectiveTilt()}. Забрав кадр, мод забрал направление прицела и снарядов,
     * но не право загнать камеру в блок; кому и эта проверка мешает, снимает её отдельно,
     * тем же {@code AcsHandle.disableCameraCollision}, что и без источника.</p>
     */
    private static void applyTiltSource(@javax.annotation.Nullable Vector3f surfaceNormal,
                                        float deltaTime, float partialTick) {
        // Моды без источников платят ровно одну проверку.
        if (TiltSources.isEmpty()) return;

        // Аренда подавления бьёт источники так же, как наш собственный наклон: катсцена обязана
        // забрать камеру независимо от того, кто её сейчас наклоняет. Тумблер мода и настройка
        // Enabled отсекаются раньше и выше — shouldApplyTilt() в CameraMixin.
        if (SuppressionLeases.isSuppressed()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        TiltSources.Winner winner = TiltSources.resolve(player, partialTick, deltaTime,
                surfaceNormal, new Quaternionf(smoothedTilt), isFirstPerson());
        if (winner == null) return;

        smoothedTilt.set(winner.tilt());
        tiltSourceMod = winner.modId();
    }

    /**
     * Накладывает сглаженный тилт поверх ванильного поворота и позиции камеры.
     *
     * <p>Считает и применяет за один заход, внутри {@code Camera#setup}. Разносить эти два
     * шага по кадру нельзя: сглаживание высоты глаза (плавное приседание) живёт в самом
     * объекте {@code Camera} и до вызова {@code setup} ещё не обновлено — см. комментарий
     * в {@code CameraMixin}.</p>
     */
    private static void applyToCamera(Camera camera, float partialTick) {
        // Сначала считаем, насколько близко стена в направлении сдвига — и масштабируем
        // ВЕСЬ наклон по этой близости (поворот + позиция + снаряды через getSmoothedTilt).
        updateWallScale(camera.getPosition(), partialTick);

        if (rotationActive()) {
            applyCameraRotation(camera);
        }
        if (posShiftActive()) {
            camera.setPosition(computeCameraPosition(camera.getPosition(), partialTick));
        }
    }

    /**
     * Текущий ПРИМЕНЯЕМЫЙ тилт — сглаженный наклон, уменьшенный по близости к стене.
     * Его используют и камера, и взгляд (перекрестие), и синхронизация на сервер,
     * поэтому всё (поворот, сдвиг, снаряды, лучи) уменьшается у стены согласованно.
     */
    public static Quaternionf getSmoothedTilt() {
        return effectiveTilt();
    }

    /**
     * Насколько наклон отодвигает точку глаза от ванильной — вектор в МИРОВЫХ координатах,
     * либо {@code null}, если вмешиваться не нужно.
     *
     * <p>Это ровно тот сдвиг, который получает камера в {@link #computeCameraPosition}:
     * точка глаза поворачивается вокруг ног на применяемый тилт. Отдельный метод нужен
     * потому, что тот же сдвиг обязаны получать все лучи «от глаза» — иначе выходит гибрид
     * из ненаклонённого начала и наклонённого направления ({@code getViewVector} тилтится
     * глобально), а такой луч не совпадает ни с перекрестием, ни с ванилой.</p>
     *
     * <p>Считается заново, а не как {@code позиция камеры − глаз}: камера обновляется в
     * {@code Camera#setup}, то есть уже после пика, и разность тащила бы отставание на кадр.
     * Здесь же поворачивается короткий вектор роста глаза, и прошлокадровость кватерниона
     * даёт ошибку второго порядка.</p>
     */
    @javax.annotation.Nullable
    public static Vec3 aimEyeOffset(float partialTick) {
        if (!Config.isLoaded()) return null;
        if (Config.LEGACY_PICK.get()) return null;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return null;

        // Проверки режима камеры здесь НЕТ намеренно. Арифметика ниже вращает точку глаза вокруг
        // ног и на позицию камеры не смотрит вовсе — в третьем лице она так же верна, как в
        // первом. А разрешение работать в третьем лице приходит само, через getPosTilt →
        // shouldApplyTilt → isThirdPersonAllowed: гейт один, дублировать его тут значит вернуть
        // долг 12.3.

        // Все прочие условия (мод включён, сдвиг позиции включён, тилт применяется)
        // спрятаны здесь — те же, что и у камеры.
        Quaternionf posTilt = com.playsi.aero_cam_sync.TiltAccess.getPosTilt(player);
        if (posTilt == null) return null;

        // Интерполированная поза кадра — это рендерный путь. Формула общая, см. TiltAccess.
        return com.playsi.aero_cam_sync.TiltAccess.eyeRotationDelta(
                player.getEyePosition(partialTick), player.getPosition(partialTick), posTilt);
    }

    /**
     * Множитель близости к стене, 0..1 — то же число, которым урезан весь наклон.
     * Уходит наружу через {@code AcsClientState.tiltScale()}: без него мод не поймёт,
     * почему наклон включён, а поправка почти нулевая.
     *
     * <p>В третьем лице и при выключенной коллизии остаётся 1 — там clear-тест не считается
     * (см. {@link #updateWallScale}).</p>
     */
    public static float tiltScale() {
        return wallScale;
    }

    /**
     * Мод, чей источник задал наклон в этом кадре, либо {@code null} — считали сами.
     * Уходит наружу через {@code AcsClientState.tiltSource()}.
     */
    @javax.annotation.Nullable
    public static String tiltSource() {
        return tiltSourceMod;
    }

    /**
     * Поворачивается ли камера в этом кадре, и следует ли за ней направление лучей.
     *
     * <p><b>Настройки {@code MODIFY_CAMERA_ROT} / {@code MODIFY_CAMERA_POS} чужой наклон не
     * режут.</b> Они делят НАШ наклон на две половины — «поверни вид» и «сдвинь точку глаза», —
     * потому что это две независимые части нашей арифметики и игрок волен выключить любую.
     * Наклон, посчитанный чужим модом, на эти половины не делится: мод вернул одно значение и
     * отвечает за него целиком, а разрешить игроку выключить половину чужого расчёта значит
     * отдать ему ровно тот гибрид (§1.6) — повёрнутый вид с несдвинутым началом луча, — ради
     * устранения которого писалось всё остальное.</p>
     *
     * <p>Выключить чужой наклон игроку по-прежнему есть чем, и это намеренно ДРУГИЕ ручки:
     * тумблер мода и {@code Enabled} гасят всё разом (проверяются раньше, в
     * {@link #shouldApplyTilt()}), а подавление по API — в {@link #applyTiltSource}.</p>
     */
    public static boolean rotationActive() {
        return tiltSourceMod != null || Config.MODIFY_CAMERA_ROT.get();
    }

    /** Сдвигается ли позиция камеры в этом кадре. Зеркало {@link #rotationActive()}. */
    public static boolean posShiftActive() {
        return tiltSourceMod != null || Config.MODIFY_CAMERA_POS.get();
    }

    private static Quaternionf effectiveTilt() {
        if (wallScale >= 0.999f) return new Quaternionf(smoothedTilt);
        return new Quaternionf().slerp(smoothedTilt, wallScale);
    }

    // -------------------------------------------------------------------------
    // Внутренняя логика
    // -------------------------------------------------------------------------

    /**
     * Считается ли коллизия камеры вообще — обе её половины сразу.
     *
     * <p>Одна ручка на всё, и это решение, а не лень: смысл ровно тот же, что у галочки
     * {@code Camera collision} в настройках, и модерам объясняем одно понятие, а не два.
     * Выключатель из API ({@code AcsHandle.disableCameraCollision}) стоит рядом с галочкой
     * потому, что мод, реально поворачивающий игрока, отвечает на тот же вопрос, что и она:
     * «проверять ли нам, что камера не в блоке». Разделение на clear-тест и кламп — потом,
     * если появится мод, которому нужна половина.</p>
     */
    private static boolean collisionEnabled() {
        return Config.CAMERA_COLLISION.get() && !CameraCollisionOverrides.isDisabled();
    }

    private static boolean isFirstPerson() {
        return Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    private static void applyCameraRotation(Camera camera) {
        Quaternionf tilt    = effectiveTilt();
        Quaternionf vanilla = new Quaternionf(camera.rotation());
        tilt.mul(vanilla);
        camera.rotation().set(tilt);
    }

    /**
     * Считает {@link #wallScale}: НАИБОЛЬШИЙ масштаб наклона (0..1), при котором точка
     * камеры находится в чистом пространстве — у неё есть зазор во ВСЕ стороны (а не
     * только вдоль сдвига) и она не за стеной от глаза. У стены — 0 (наклона нет).
     * Уменьшение быстрое (чтобы не успеть залезть в блок), возврат — плавный.
     *
     * <h2>⚠ В ТРЕТЬЕМ ЛИЦЕ НЕ СЧИТАЕТСЯ ВОВСЕ</h2>
     *
     * <p>Весь поиск держится на инварианте, который нигде не проверяется: «масштаб 0 (позиция
     * глаза) заведомо чист». В третьем лице он ЛОЖЕН ПО ПОСТРОЕНИЮ. Ванильный
     * {@code Camera#getMaxZoom} щупает углы на 0.1 и останавливает камеру ровно на попадании,
     * а наш clear-тест щупает на 0.15 — то есть при {@code s = 0} он ловит ту самую стену, к
     * которой ваниль камеру и прижала. Поиск честно сходится в 0, {@code effectiveTilt}
     * становится identity, горизонт выравнивается, {@code tiltApplied()} переворачивается в
     * {@code false}. Не «иногда ошибается» — систематически даёт «занято» на позиции, которую
     * ваниль специально выбрала как безопасную.</p>
     *
     * <p><b>Почему выключить это безопасно.</b> X-ray бывает только от СДВИГА:
     * {@link #applyCameraRotation} меняет один кватернион и камеру никуда не двигает, повернуть
     * камеру и получить просвет сквозь блок физически нельзя. За сдвигом продолжает следить
     * {@code clampToCollision} в {@link #computeCameraPosition} — и в третьем лице у него
     * ЧЕСТНЫЙ якорь: ванильную позицию камеры гарантирует сама ваниль. В первом лице такой
     * гарантии нет (там якорь и есть точка глаза), поэтому первое лицо не трогаем вообще.</p>
     *
     * <p>Побочный эффект: бинарный поиск — это до ~180 клипов на кадр, и в третьем лице их
     * больше нет.</p>
     */
    static void updateWallScale(Vec3 vanillaCamPos, float partialTick) {
        float target = 1.0f;

        // Спрашиваем posShiftActive(), а не настройку: под чужим источником позиция
        // сдвигается независимо от неё, и не посчитать здесь масштаб значило бы пустить
        // камеру в блок с выключенной у игрока галочкой сдвига.
        if (posShiftActive() && collisionEnabled() && isFirstPerson()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                double feetX = Mth.lerp(partialTick, player.xOld, player.getX());
                double feetY = Mth.lerp(partialTick, player.yOld, player.getY());
                double feetZ = Mth.lerp(partialTick, player.zOld, player.getZ());

                Vec3 eye = vanillaCamPos;
                ClientSubLevel sl = SubLevelTracker.getCachedSubLevel();
                Pose3dc pose = null;
                try { if (sl != null) pose = sl.renderPose(partialTick); }
                catch (Throwable ignored) { pose = null; }

                Vector3f eyeOffset = new Vector3f(
                        (float) (eye.x - feetX),
                        (float) (eye.y - feetY),
                        (float) (eye.z - feetZ));

                boolean prev = LevelClipMixinState.inTiltedClip;
                LevelClipMixinState.inTiltedClip = true;
                try {
                    // Бинарный поиск
                    if (cameraClear(player, eye, wallScaleCamPos(feetX, feetY, feetZ, eyeOffset, 1.0f), sl, pose)) {
                        target = 1.0f; // s = 1 чист — стен рядом нет, дальше можно не искать
                    } else {
                        // ⚠ lo = 0 — это ДОПУЩЕНИЕ «позиция глаза (масштаб 0) чиста», а не
                        // проверка. Оно ложно в третьем лице (отсечено выше) и у мода, реально
                        // поворачивающего игрока (для него — выключатель через API). Если оно
                        // ложно, поиск честно сходится в 0 и наклон гаснет целиком.
                        float lo = 0.0f;
                        float hi = 1.0f; // известно, что заблокировано
                        for (int i = 0; i < 10; i++) {
                            float mid = (lo + hi) * 0.5f;
                            Vec3 cam = wallScaleCamPos(feetX, feetY, feetZ, eyeOffset, mid);
                            if (cameraClear(player, eye, cam, sl, pose)) lo = mid; else hi = mid;
                        }
                        target = lo;
                    }
                } finally {
                    LevelClipMixinState.inTiltedClip = prev;
                }
            }
        }

        // Смена режима камеры — МГНОВЕННО, без сглаживания. Полупериод спуска ≈0.35 тика, при
        // 60 fps это ~5 кадров (~80 мс) от 1 до цели, а сам переход между режимами мгновенный.
        // Без сброса: 3-е → 1-е у стены — все эти кадры камера внутри блока; 1-е → 3-е — рывок
        // наклона на переходе. Флаг ставит tickApplyState (он зовётся безусловно), поэтому
        // переход ловится и когда наклон в третьем лице не считается вовсе.
        if (cameraModeChanged) {
            cameraModeChanged = false;
            wallScale = target;
            return;
        }

        // Экспоненциальное сглаживание уже само быстрое, когда далеко от цели, и мягкое
        // у самой цели — поэтому ощущается плавно и при этом не успевает залезть в блок.
        // Полупериод настраивается; возврат наклона чуть плавнее спуска.
        float dt = Minecraft.getInstance().getTimer().getRealtimeDeltaTicks();
        float smooth = Config.CAMERA_COLLISION_SMOOTH.get().floatValue();
        float halfLife = (target < wallScale) ? smooth : smooth * 1.5f;
        float a = (halfLife <= 1.0e-4f) ? 1f : 1f - (float) Math.pow(0.5, dt / halfLife);
        wallScale = Mth.lerp(a, wallScale, target);
    }

    /** Позиция камеры при масштабе наклона {@code s} относительно ног игрока. */
    private static Vec3 wallScaleCamPos(double feetX, double feetY, double feetZ,
                                        Vector3f eyeOffset, float s) {
        Vector3f off = new Quaternionf().slerp(smoothedTilt, s).transform(new Vector3f(eyeOffset));
        return new Vec3(feetX + off.x, feetY + off.y, feetZ + off.z);
    }

    /** Камера в чистом пространстве: не за стеной от глаза И есть зазор во все стороны. */
    private static boolean cameraClear(LocalPlayer player, Vec3 eye, Vec3 cam,
                                       ClientSubLevel sl, Pose3dc pose) {
        Level level = player.level();

        // 1) Между глазом и камерой нет стены (камера не за/внутри блока).
        if (blockedWorld(level, player, eye, cam)) return false;
        if (blockedSub(sl, pose, player, eye, cam)) return false;

        // 2) Зазор вокруг камеры (ближняя плоскость) — по 8 диагональным углам (как в
        // collisionMaxDist), а не только по 6 осям: осевые лучи не ловят блок, в который
        // камера упирается диагонально углом, из-за чего на некоторых углах наклона
        // wallScale ошибочно считал позицию чистой.
        final double R = 0.15;
        for (int i = 0; i < 8; i++) {
            double sx = (i & 1) != 0 ? R : -R;
            double sy = (i & 2) != 0 ? R : -R;
            double sz = (i & 4) != 0 ? R : -R;
            Vec3 to = cam.add(sx, sy, sz);
            if (blockedWorld(level, player, cam, to)) return false;
            if (blockedSub(sl, pose, player, cam, to)) return false;
        }
        return true;
    }

    private static boolean blockedWorld(Level level, LocalPlayer player, Vec3 from, Vec3 to) {
        ClipContext ctx = new ClipContext(from, to,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player);
        ((ClipContextExtension) ctx).sable$setDoNotProject(true);
        // Сеть обязана пройти мимо: этот луч не прицельный, он и вычисляет ту самую поправку,
        // которую сеть прибавляет. Иначе коллизия камеры считалась бы от своего результата.
        ClipNet.suppress();
        try {
            return level.clip(ctx).getType() != HitResult.Type.MISS;
        } finally {
            ClipNet.resume();
        }
    }

    private static boolean blockedSub(ClientSubLevel sl, Pose3dc pose, LocalPlayer player,
                                      Vec3 from, Vec3 to) {
        if (sl == null || pose == null) return false;
        Vec3 lf = pose.transformPositionInverse(from);
        Vec3 lt = pose.transformPositionInverse(to);
        ClipContext ctx = new ClipContext(lf, lt,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player);
        ((ClipContextExtension) ctx).sable$setDoNotProject(true);
        ClipNet.suppress();
        try {
            return sl.getLevel().clip(ctx).getType() != HitResult.Type.MISS;
        } finally {
            ClipNet.resume();
        }
    }

    /**
     * Итоговая позиция камеры при наклоне: ванильная точка, повёрнутая вокруг ног и
     * прижатая коллизией.
     *
     * @param vanillaCamPos позиция камеры ДО нашего вмешательства
     */
    static Vec3 computeCameraPosition(Vec3 vanillaCamPos, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return vanillaCamPos;

        double feetX = Mth.lerp(partialTick, player.xOld, player.getX());
        double feetY = Mth.lerp(partialTick, player.yOld, player.getY());
        double feetZ = Mth.lerp(partialTick, player.zOld, player.getZ());

        // Сдвиг считается уже УМЕНЬШЕННЫМ наклоном (по близости к стене) — у стены он сам
        // сходит к нулю, поэтому камера не лезет в блок. Тот же тилт уходит на снаряды/лучи.
        Vector3f offset = effectiveTilt().transform(new Vector3f(
                (float)(vanillaCamPos.x - feetX),
                (float)(vanillaCamPos.y - feetY),
                (float)(vanillaCamPos.z - feetZ)));

        double targetX = feetX + offset.x;
        double targetY = feetY + offset.y;
        double targetZ = feetZ + offset.z;

        // Сдвиг камеры может занести её ВНУТРЬ соседней стены → видно сквозь блоки (X-ray).
        // Как ванильная камера от 3-го лица: прокидываем луч от глаза к желаемой позиции и
        // не пускаем камеру за ближайшую стену.
        //
        // ⚠ От режима камеры этот кламп НЕ зависит и зависеть не должен — это единственная
        // защита от X-ray при повороте плеча, а ваниль считала зум ДО нашего поворота и про
        // повёрнутую точку ничего не обещала. В третьем лице плечо до 4 блоков, и наклон в 20°
        // уводит камеру примерно на 1.4 блока вбок. Выключает его только галочка игрока и
        // выключатель мода — см. collisionEnabled().
        if (collisionEnabled()) {
            Vec3 clamped = clampToCollision(player, vanillaCamPos,
                    new Vec3(targetX, targetY, targetZ), partialTick);
            targetX = clamped.x;
            targetY = clamped.y;
            targetZ = clamped.z;
        }

        return new Vec3(targetX, targetY, targetZ);
    }

    /**
     * Ограничивает позицию камеры так, чтобы между {@code anchor} (глаз — заведомо
     * безопасная точка) и камерой не было сплошного блока — ни МИРА, ни СУБЛЕВЕЛА
     * (палубы). Восемь смещённых лучей (как в ванильном {@code Camera#getMaxZoom})
     * учитывают ближнюю плоскость отсечения, чтобы камера не утыкалась вплотную.
     */
    private static Vec3 clampToCollision(LocalPlayer player, Vec3 anchor, Vec3 desired, float partialTick) {
        Vec3 delta = desired.subtract(anchor);
        double dist = delta.length();
        if (dist < 1.0e-4) return desired;

        float maxDist = collisionMaxDist(player, anchor, desired, partialTick);
        if (maxDist >= dist) return desired;
        return anchor.add(delta.scale(maxDist / dist));
    }

    /** @return расстояние от {@code anchor} до ближайшей стены вдоль отрезка (или его длина, если стен нет). */
    private static float collisionMaxDist(LocalPlayer player, Vec3 anchor, Vec3 desired, float partialTick) {
        Level level = player.level();
        Vec3 delta = desired.subtract(anchor);
        double dist = delta.length();
        if (dist < 1.0e-4) return 0f;

        // Сублевел, на котором стоим (для клипа по палубе в его локальной системе).
        ClientSubLevel subLevel = SubLevelTracker.getCachedSubLevel();
        Pose3dc subPose = null;
        try {
            if (subLevel != null) subPose = subLevel.renderPose(partialTick);
        } catch (Throwable ignored) { subPose = null; }

        float maxDist = (float) dist;

        // Клип без сдвига — ни старым ClipShifter'ом (откат), ни сетью (основной путь):
        // иначе проверка на стену считалась бы от уже сдвинутой камеры, то есть от результата
        // собственной работы. Ран 2026-08-06 поймал эту петлю в уловах сети.
        boolean prev = LevelClipMixinState.inTiltedClip;
        LevelClipMixinState.inTiltedClip = true;
        ClipNet.suppress();
        try {
            for (int i = 0; i < 8; i++) {
                Vec3 corner = new Vec3(
                        (i & 1) * 2 - 1,
                        (i >> 1 & 1) * 2 - 1,
                        (i >> 2 & 1) * 2 - 1
                ).scale(0.1);

                Vec3 from = anchor.add(corner);
                Vec3 to = desired.add(corner);

                // 1) МИР. Sable @Overwrite'ит clip и, если начало внутри AABB контраптиона
                //    (а мы на нём стоим), проецирует точку в дальний плот — луч становится
                //    бредом. doNotProject=true → ванильный мировой клип, реально ловит рельеф.
                ClipContext ctx = new ClipContext(from, to,
                        ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player);
                ((ClipContextExtension) ctx).sable$setDoNotProject(true);
                HitResult hit = level.clip(ctx);
                if (hit.getType() != HitResult.Type.MISS) {
                    float d = (float) hit.getLocation().distanceTo(anchor);
                    if (d < maxDist) maxDist = d;
                }

                // 2) СУБЛЕВЕЛ (палуба). Переводим луч в локальную систему плота и клипаем
                //    его напрямую (doNotProject, без повторной проекции). Поза — жёсткое
                //    преобразование, поэтому расстояние в локальной системе == мировому.
                if (subPose != null) {
                    Vec3 lFrom = subPose.transformPositionInverse(from);
                    Vec3 lTo = subPose.transformPositionInverse(to);
                    ClipContext lctx = new ClipContext(lFrom, lTo,
                            ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player);
                    ((ClipContextExtension) lctx).sable$setDoNotProject(true);
                    HitResult lhit = subLevel.getLevel().clip(lctx);
                    if (lhit.getType() != HitResult.Type.MISS) {
                        float d = (float) lhit.getLocation().distanceTo(lFrom);
                        if (d < maxDist) maxDist = d;
                    }
                }
            }
        } finally {
            ClipNet.resume();
            LevelClipMixinState.inTiltedClip = prev;
        }

        return maxDist;
    }
}