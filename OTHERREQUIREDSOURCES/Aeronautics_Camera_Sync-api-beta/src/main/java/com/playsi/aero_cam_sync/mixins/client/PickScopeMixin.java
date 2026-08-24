package com.playsi.aero_cam_sync.mixins.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.playsi.aero_cam_sync.AeroCamSync;
import com.playsi.aero_cam_sync.ClipNet;
import com.playsi.aero_cam_sync.TiltAccess;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.debug.DebugRayRenderer;
import com.playsi.aero_cam_sync.client.debug.PickDiagnostics;
import com.playsi.aero_cam_sync.client.tilt.CameraController;
import com.playsi.aero_cam_sync.client.aim.PickScope;
import com.playsi.aero_cam_sync.client.aim.RenderEyeScope;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Открывает {@link PickScope} вокруг ВСЕГО метода {@code GameRenderer#pick(F)}.
 *
 * <p>Всё, что происходит внутри пика — ванильные лучи, обёртки Cut Through, TAIL-инжекты
 * сторонних модов (рычаг газа из Create Simulated) — оказывается внутри окна. Мы не
 * соревнуемся за право записать {@code mc.hitResult} последним; мы задаём условия, в
 * которых считают все.</p>
 *
 * <p><b>Почему сам метод, а не площадка вызова в {@code renderLevel}.</b> Так было
 * сделано сначала — по образцу Sable, который оборачивает именно тот вызов, чтобы
 * поставить свой pose-provider ({@code clip_overwrite/GameRendererMixin#sable$renderLevel}).
 * Замер 2026-08-03 показал, что за кадр {@code pick(F)} зовут ДВА раза, и второй вызов идёт
 * мимо площадки в {@code renderLevel}: он считал от ненаклонённого глаза и перезаписывал
 * {@code mc.hitResult} последним, поэтому клик ставил блок по ванильному лучу
 * (диагностика: {@code picks=2 lastPick=UNSCOPED:…}, и {@code sent} совпадал с ним, а не
 * с нашим результатом). Обёртка вокруг метода накрывает любого вызывающего.</p>
 *
 * <p><b>Origin — позиция главной камеры, посчитанная в ПРОШЛОМ кадре.</b> {@code pick(F)}
 * вызывается раньше, чем {@code Camera#setup}, поэтому на момент пика камера ещё не
 * пересчитана. Это давно известное расхождение (§1.1 подводных камней), и оно ровно такое же,
 * каким было у старого {@code GameRendererPickMixin} — то есть не регресс.
 * Попытка убрать его расчётом наклона в начале кадра откачена 2026-08-02: она ломала
 * плавное приседание (сглаживание высоты глаза живёт в объекте {@code Camera}) и выводила
 * запись наклона из-под гварда главной камеры. Подробности — в комментарии
 * {@code CameraMixin}.</p>
 */
@Mixin(GameRenderer.class)
public abstract class PickScopeMixin {

    @WrapMethod(method = "pick(F)V")
    private void aero$scopedPick(float partialTick, Operation<Void> original) {
        // Считаем пики от нашего вызова и дальше: если к моменту клика их больше одного,
        // значит кто-то пикает ещё раз уже после нас, мимо окна.
        PickDiagnostics.resetPickCount();

        // Страховка: если чьё-то исключение оставило окно «настоящего глаза» открытым,
        // наклон отключился бы навсегда и молча. Пик — естественная точка сброса.
        RenderEyeScope.reset();

        Vec3 origin = pickOrigin(partialTick);
        Vec3 offset = origin == null ? null : tiltOffset(partialTick);
        if (origin == null || offset == null) {
            if (offset == null && origin != null) PickDiagnostics.recordScope("off:no-tilt");
            original.call(partialTick);
            // Кадр без окна — пик полностью ванильный. Записываем результат всё равно, иначе
            // в диагностике осталось бы значение прошлого кадра и клик выглядел бы так,
            // будто hitResult кто-то переписал.
            recordFrame(partialTick, null);
            return;
        }

        PickDiagnostics.recordScope("on");
        submitDebugRays(origin, partialTick);

        PickScope.open(origin);

        // ЭКСПЕРИМЕНТ (2026-08-03): поза саблевела для тикового пика.
        //
        // Поза у Sable — стек, на дне которого logicalPose(). sable$renderLevel кладёт сверху
        // renderPose(f) ТОЛЬКО на кадровый вызов pick. Тиковый (Minecraft#tick -> pick(1.0F)),
        // результат которого и уходит в клик, считается по logicalPose — то есть по другой позе,
        // чем та, в которой палуба нарисована. На движущейся палубе разница = интерполяция кадра.
        //
        // Кладём renderPose сами. Если Sable уже положил её (кадровый вызов), повторный push
        // того же смысла безвреден — стек просто станет глубже на один.
        LevelPoseProviderExtension poses = (LevelPoseProviderExtension) Minecraft.getInstance().level;
        poses.sable$pushPoseSupplier(sub -> ((ClientSubLevel) sub).renderPose(partialTick));
        try {
            original.call(partialTick);
        } finally {
            poses.sable$popPoseSupplier();
            recordFrame(partialTick, origin);
            PickScope.close();
        }
    }

    /**
     * Раз в ~секунду под {@code DEBUG_MESSAGES} печатает, сработала ли схема.
     *
     * <p>Читать так: {@code subs=0} значит, что окно открылось, но до воронки Sable дело не
     * дошло — луч построили мимо неё, и пик остался ванильным. {@code shift} — насколько
     * origin разошёлся с настоящим глазом; при наклоне он обязан быть заметно больше нуля.</p>
     */
    private static void recordFrame(float partialTick, Vec3 origin) {
        // Тот же гейт, что у остальных записей — заодно переживает незагруженный конфиг.
        if (!PickDiagnostics.enabled()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // Саблевел дрейфует, поэтому сравнивать пик с кликом можно только в пределах одного
        // кадра. Запоминаем оба результата каждый кадр — строку клика печатает DebugUseItemMixin.
        PickDiagnostics.lastTilted = describe(Minecraft.getInstance().hitResult);
        PickDiagnostics.lastVanilla = describe(vanillaPick(player, partialTick));

        if (++frameCounter % 60 != 0) return;

        Vec3 eye = player.getEyePosition(partialTick);
        AeroCamSync.LOGGER.info(
                "[AeroCamSync] pick: scope={} subs={} shift={} tiltErr={}° camDelta={} tilted={} vanilla={}",
                PickDiagnostics.lastScope,
                PickScope.substitutions(),
                origin == null ? "-" : String.format("%.3f", origin.distanceTo(eye)),
                tiltError(player, partialTick),
                PickDiagnostics.lastEyeDelta,
                PickDiagnostics.lastTilted,
                PickDiagnostics.lastVanilla);
    }

    /**
     * ВРЕМЕННЫЙ ЗАМЕР (2026-08-03). Угол между нашим наклоном и настоящей позой палубы.
     *
     * <p>Мы наклоняем луч сглаженным и уменьшенным у стен {@code getSmoothedTilt()}, а моды,
     * которые разворачивают луч обратно в систему палубы (Dashpanels — через
     * {@code renderPose().transformNormalInverse}), снимают с него НАСТОЯЩУЮ ориентацию.
     * Остаток — это и есть их промах. На блочном пике он незаметен (пара градусов на пяти
     * блоках даёт меньше блока), на кнопке панели — фатален.</p>
     */
    private static String tiltError(LocalPlayer player, float partialTick) {
        var sub = dev.ryanhcode.sable.Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(sub instanceof ClientSubLevel client)) return "-";

        Quaternionf ours = CameraController.getSmoothedTilt();
        var deck = client.renderPose(partialTick).orientation();

        double dot = Math.abs(ours.x * deck.x() + ours.y * deck.y()
                + ours.z * deck.z() + ours.w * deck.w());
        return String.format("%.2f", Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot))));
    }

    /**
     * Тот же блочный луч, но из НЕнаклонённого глаза и по сырому направлению — для сравнения
     * в логе. Считается вне {@link PickScope}, поэтому подмена глаза его не касается.
     */
    private static BlockHitResult vanillaPick(LocalPlayer player, float partialTick) {
        Vec3 from = player.getEyePosition(partialTick);
        Vec3 dir = player.calculateViewVector(player.getViewXRot(partialTick),
                player.getViewYRot(partialTick));
        Vec3 to = from.add(dir.scale(player.blockInteractionRange()));
        // Сеть обязана пройти мимо: этот луч и есть эталон «как было бы без нас». Она его
        // ловила (ран 2026-08-06) — начало-то ровно в ванильном глазу — и колонка vanilla=
        // в логе переставала быть эталоном.
        ClipNet.suppress();
        try {
            return player.level().clip(new ClipContext(
                    from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        } finally {
            ClipNet.resume();
        }
    }

    /** {@code BLOCK@x,y,z face} — чтобы было видно, расходятся лучи блоком или только гранью. */
    private static String describe(HitResult hit) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) return "MISS";
        if (hit instanceof BlockHitResult block) {
            return "BLOCK@" + block.getBlockPos().toShortString() + " " + block.getDirection();
        }
        return hit.getType().toString();
    }

    private static int frameCounter = 0;

    /**
     * Начало луча на этот пик, либо {@code null} — вмешиваться не нужно и пик остаётся
     * полностью ванильным.
     *
     * <p>Условия намеренно совпадают с теми, при которых наклоняется камера: иначе луч
     * разъедется с перекрестием.</p>
     *
     * <p><b>Источник начала разный по режиму камеры, и это не оптимизация.</b> В первом лице
     * позиция главной камеры И ЕСТЬ повёрнутая точка глаза, поэтому её можно просто прочитать —
     * заодно бесплатно достаётся прижатие коллизией. В третьем лице камера вынесена за спину
     * игрока, и читать её нельзя: луч стартовал бы оттуда. Там точка глаза считается.</p>
     */
    private static Vec3 pickOrigin(float partialTick) {
        // Ранние кадры: спека клиентского конфига может быть ещё не загружена (Issue #19).
        if (!Config.isLoaded()) return deny("config");
        if (Config.LEGACY_PICK.get()) return deny("legacy");
        if (!Config.MOD_ENABLED.get()) return deny("disabled");
        // Сдвиг позиции выключен — камера стоит в глазу, подменять нечего.
        if (!Config.MODIFY_CAMERA_POS.get()) return deny("no-pos");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return deny("no-player");
        // Третье лицо — только по включателю из API. Проверка стоит ПЕРЕД shouldApplyTilt(),
        // хотя тот же гейт сидит и внутри него: иначе диагностика отвечала бы невнятным
        // "no-tilt-state" на самый частый вопрос «почему в третьем лице ванильный пик».
        if (!mc.options.getCameraType().isFirstPerson()
                && !CameraController.isThirdPersonAllowed()) return deny("third-person");
        if (!CameraController.shouldApplyTilt()) return deny("no-tilt-state");

        // Первое лицо: позиция главной камеры уже наклонена и прижата коллизией
        // (CameraMixin -> Camera#setup), и она же есть повёрнутая точка глаза.
        if (mc.options.getCameraType().isFirstPerson()) {
            return mc.gameRenderer.getMainCamera().getPosition();
        }

        // Третье лицо: камера стоит ЗА СПИНОЙ игрока (у ванили ~4 блока), и началом луча ей быть
        // нельзя — он стартовал бы позади игрока и бил мимо перекрестия. Считаем ту же точку,
        // с которой камера совпадает в первом лице: глаз, повёрнутый вокруг ног.
        //
        // Ровно этот дефект поймал прогон 2026-08-16: в логе стояли shift=4,780 и camDelta=4,000,
        // то есть длина ванильного выноса камеры, а не сдвиг глаза (он меньше роста игрока).
        Vec3 offset = tiltOffset(partialTick);
        if (offset == null) return deny("no-tilt");
        return mc.player.getEyePosition(partialTick).add(offset);
    }

    /** Запоминает, какой именно гейт закрыл окно, и возвращает null. Только для диагностики. */
    private static Vec3 deny(String reason) {
        PickDiagnostics.recordScopeDenied(reason);
        return null;
    }

    /**
     * Насколько наклон отодвигает глаз — тот же поворот точки глаза вокруг ног, что делает
     * камера и что уже применяют {@code ItemPovTiltMixin} / {@code CreateRaycastTiltMixin}.
     *
     * <p>Гейтов здесь нет намеренно: их держит {@link #pickOrigin(float)}, и держит один раз
     * на пик. Формула — общая, {@code TiltAccess.eyeRotationDelta}; отличие этого вызывающего
     * только во времени: интерполированная поза кадра, потому что путь рендерный.</p>
     */
    private static Vec3 tiltOffset(float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;

        Quaternionf posTilt = TiltAccess.getPosTilt(player);
        if (posTilt == null) return null;

        return TiltAccess.eyeRotationDelta(
                player.getEyePosition(partialTick), player.getPosition(partialTick), posTilt);
    }

    private static void submitDebugRays(Vec3 origin, float partialTick) {
        if (!Config.DEBUG_PICK_RAYS.get()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        Vec3 vanilla = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick).scale(player.blockInteractionRange());
        DebugRayRenderer.submitPickRay(vanilla, vanilla.add(look), 0.5f, 0.5f, 0.5f);

        // Жёлтый — настоящее начало луча: глаз плюс наклонная поправка. Позицию камеры тут
        // рисовать нельзя, иначе отладка показывала бы не тот луч, который реально считается.
        Vec3 offset = tiltOffset(partialTick);
        Vec3 start = offset == null ? vanilla : vanilla.add(offset);
        DebugRayRenderer.submitPickRay(start, start.add(look), 1.0f, 1.0f, 0.0f);
    }
}
