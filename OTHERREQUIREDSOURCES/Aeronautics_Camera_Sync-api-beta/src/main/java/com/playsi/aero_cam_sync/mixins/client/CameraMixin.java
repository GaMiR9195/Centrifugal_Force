package com.playsi.aero_cam_sync.mixins.client;

import com.playsi.aero_cam_sync.SideManager;
import com.playsi.aero_cam_sync.apiimpl.SuppressionLeases;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.debug.DebugRayRenderer;
import com.playsi.aero_cam_sync.client.tilt.BlacklistHandle;
import com.playsi.aero_cam_sync.client.tilt.CameraController;
import com.playsi.aero_cam_sync.client.sublevel.SubLevelTracker;
import com.playsi.aero_cam_sync.client.tilt.SurfaceRaycaster;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Camera.class, priority = 1300)
public abstract class CameraMixin {

    @Inject(method = "setup", at = @At("TAIL"))
    private void applyTerrainTilt(
            BlockGetter level, Entity entity,
            boolean detached, boolean thirdPersonReverse,
            float partialTick, CallbackInfo ci) {

        Minecraft mc = Minecraft.getInstance();

        // Тилт — ТОЛЬКО для главной игровой камеры. Некоторые экраны (напр. DiagramScreen
        // из Create Simulated) рендерят второй вид СВОЕЙ отдельной камерой, для которой
        // Camera#setup тоже вызывается. Наклон и коллизия (wallScale) хранятся глобально;
        // вторичная камера пересчитывала общий wallScale от своей позиции и роняла его →
        // пила wallScale → джиттер наклона на главном виде при открытом экране.
        //
        // Проверка теперь возвращает ПРАВО НА ЗАПИСЬ, а не булево: вторичная камера получает
        // null и физически не может тронуть состояние, даже если этот ранний выход когда-нибудь
        // перепишут. См. CameraController.Frame.
        CameraController.Frame frame = CameraController.forMainCamera((Camera) (Object) this);
        if (frame == null) return;

        // Ванильная камера — снимается ЗДЕСЬ, до любого нашего вмешательства и до всех
        // остальных выходов из метода. Публичное API обязано отдавать ненаклонённые значения
        // и в кадрах, где наклона нет вовсе; восстановить их обратным поворотом нельзя —
        // применяемый наклон урезан wallScale, а тот меняется в течение кадра
        // (см. FrameVanillaState).
        frame.captureVanilla((Camera) (Object) this);

        // ВНИМАНИЕ: расчёт наклона обязан оставаться ЗДЕСЬ, за гвардом главной камеры.
        // Попытка перенести его в начало кадра (до pick, чтобы луч и картинка читали одно
        // число) провалилась дважды и была откачена 2026-08-02:
        //   1) позиция камеры считалась из Sable.HELPER.getEyePositionInterpolated, а он берёт
        //      СЫРУЮ entity.getEyeHeight(); ванильная Camera#setup использует
        //      Mth.lerp(pt, eyeHeightOld, eyeHeight), и именно это сглаживание даёт плавное
        //      приседание. Подмена позиции его убивала — присед дёргался;
        //   2) запись в CameraController уезжала из-под этого гварда, и вторичная камера
        //      экрана диаграммы снова начинала портить общее состояние (§2).
        // Пик берёт origin из уже наклонённой камеры (PickScopeMixin) — на кадр позже, но
        // это давно известное и терпимое поведение, а не регресс.
        frame.tickApplyState();

        if (!Config.MOD_ENABLED.get()) return;
        if (!CameraController.shouldApplyTilt()) return;

        float deltaTime = mc.getTimer().getRealtimeDeltaTicks();

        ClientSubLevel subLevel = SubLevelTracker.getClientSubLevel(mc.player);

        DebugRayRenderer.clear();

        Vector3f surfaceNormal = null;

        // Авто-отключение для снарядов/вёдер нужно ТОЛЬКО когда сервер без мода и не
        // может наклонить снаряд сам. Если мод есть на сервере — оставляем наклон,
        // а сервер развернёт снаряд/луч под камеру (ProjectileShootTiltMixin и пр.).
        //
        // Сюда же присоединяется подавление по API: точка вмешательства одна и уже проверена
        // живьём. Дальше всё происходит само — целевая нормаль становится null,
        // updateSmoothedTilt уводит кватернион к единице за обычное время сглаживания, а
        // взгляд, лучи, снаряды и синхронизация на сервер следуют за ним, потому что читают
        // один и тот же getSmoothedTilt(). Тумблер I так делать НЕЛЬЗЯ: он выходит из метода
        // в начале и даёт рывок, а катсцена, начинающаяся с рывка камеры, — это баг-репорт.
        boolean banned =
                SuppressionLeases.isSuppressed()
                || (Config.CLIENT_BLACKLIST_ENABLED.get() &&
                        BlacklistHandle.holdBannedItem(Config.CLIENT_BLACKLIST_IDS.get()))
                || (Config.AUTO_DISABLE_FOR_RAYCAST_ITEMS.get() &&
                        SideManager.isClientOnly() &&
                        BlacklistHandle.holdRaycastItem());

        if (!banned && subLevel != null
                && com.playsi.aero_cam_sync.client.sublevel.SubLevelThresholds.passes(subLevel)) {
            Pose3dc pose = subLevel.renderPose(partialTick);
            surfaceNormal = SurfaceRaycaster.getSurfaceNormal(subLevel, pose);

            if (surfaceNormal == null && Config.DROP_CACHE_ON_ALL_MISS.get()) {
                SubLevelTracker.invalidateCache();
            }
        }

        frame.updateSmoothedTilt(surfaceNormal, deltaTime, partialTick, false);
        frame.applyTiltToCamera((Camera)(Object) this, partialTick);
    }
}