package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess;
import com.playsi.aero_cam_sync.api.AcsClientState;
import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.tilt.CameraController;
import com.playsi.aero_cam_sync.client.camera.FrameVanillaState;
import com.playsi.aero_cam_sync.client.sublevel.SubLevelTracker;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Клиентская половина снимка.
 *
 * <p><b>Класс клиентский, хоть и лежит в общем пакете.</b> Он держит {@link ClientSubLevel}, и
 * загружаться на выделенном сервере ему нельзя. Единственная точка входа — {@link #capture()},
 * которая возвращает ИНТЕРФЕЙС: {@code AcsStateImpl} зовёт её только внутри ветки
 * {@code isClientSide}, поэтому на сервере ссылка не исполняется и класс не грузится. Тот же
 * приём, что в {@code TiltAccess} и {@code SideGate} (краш хендшейка уже был — Issue #33).</p>
 */
final class AcsClientStateImpl implements AcsClientState {

    private final Vec3 vanillaCameraPos;
    private final Vec3 cameraPos;
    private final Quaternionf vanillaCameraRot;
    private final Quaternionf cameraRot;
    private final float tiltScale;
    private final boolean firstPerson;
    private final boolean legacyPick;
    private final boolean cameraCollisionDisabled;
    private final List<String> cameraCollisionDisabledBy;
    private final boolean thirdPersonEnabled;
    private final List<String> thirdPersonEnabledBy;
    private final @Nullable String tiltSource;
    private final @Nullable ClientSubLevel tiltSubLevel;

    private AcsClientStateImpl(Vec3 vanillaCameraPos, Vec3 cameraPos,
                               Quaternionf vanillaCameraRot, Quaternionf cameraRot,
                               float tiltScale, boolean firstPerson, boolean legacyPick,
                               boolean cameraCollisionDisabled,
                               List<String> cameraCollisionDisabledBy,
                               boolean thirdPersonEnabled,
                               List<String> thirdPersonEnabledBy,
                               @Nullable String tiltSource,
                               @Nullable ClientSubLevel tiltSubLevel) {
        this.vanillaCameraPos = vanillaCameraPos;
        this.cameraPos = cameraPos;
        this.vanillaCameraRot = vanillaCameraRot;
        this.cameraRot = cameraRot;
        this.tiltScale = tiltScale;
        this.firstPerson = firstPerson;
        this.legacyPick = legacyPick;
        this.cameraCollisionDisabled = cameraCollisionDisabled;
        this.cameraCollisionDisabledBy = cameraCollisionDisabledBy;
        this.thirdPersonEnabled = thirdPersonEnabled;
        this.thirdPersonEnabledBy = thirdPersonEnabledBy;
        this.tiltSource = tiltSource;
        this.tiltSubLevel = tiltSubLevel;
    }

    static AcsClientState capture() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();

        Vec3 cameraPos = camera.getPosition();
        Quaternionf cameraRot = new Quaternionf(camera.rotation());

        // Пока камера ни разу не проходила через Camera#setup (ранние кадры, главное меню),
        // ванильного значения просто нет — отдаём текущее, оно же и есть ненаклонённое.
        boolean captured = FrameVanillaState.hasValue();
        Vec3 vanillaPos = captured ? FrameVanillaState.pos() : cameraPos;
        Quaternionf vanillaRot = captured ? FrameVanillaState.rot() : new Quaternionf(cameraRot);

        return new AcsClientStateImpl(
                vanillaPos,
                cameraPos,
                vanillaRot,
                cameraRot,
                CameraController.tiltScale(),
                mc.options.getCameraType().isFirstPerson(),
                ClientTiltAccess.isLegacyPick(),
                CameraCollisionOverrides.isDisabled(),
                CameraCollisionOverrides.holders(),
                // Реестр, а не снимок кадра и не эффективное значение — как у коллизии рядом:
                // снимок отвечает на «работает ли сейчас», а снаружи спрашивают «кто просил».
                ThirdPersonOverrides.isEnabled(),
                ThirdPersonOverrides.holders(),
                // А это, наоборот, ЗНАЧЕНИЕ КАДРА, а не реестр: вопрос здесь другой — не «кто
                // зарегистрировался», а «кто задал наклон, который вы сейчас видите». Источник,
                // не претендовавший в этом кадре, не назван.
                CameraController.tiltSource(),
                SubLevelTracker.getCachedSubLevel());
    }

    @Override public Vec3 vanillaCameraPos() { return vanillaCameraPos; }
    @Override public Vec3 cameraPos() { return cameraPos; }
    @Override public Quaternionf vanillaCameraRot() { return new Quaternionf(vanillaCameraRot); }
    @Override public Quaternionf cameraRot() { return new Quaternionf(cameraRot); }
    @Override public float tiltScale() { return tiltScale; }
    @Override public boolean firstPerson() { return firstPerson; }
    @Override public boolean legacyPick() { return legacyPick; }
    @Override public boolean cameraCollisionDisabled() { return cameraCollisionDisabled; }
    @Override public List<String> cameraCollisionDisabledBy() { return cameraCollisionDisabledBy; }
    @Override public boolean thirdPersonEnabled() { return thirdPersonEnabled; }
    @Override public List<String> thirdPersonEnabledBy() { return thirdPersonEnabledBy; }
    @Override public @Nullable String tiltSource() { return tiltSource; }
    @Override public @Nullable ClientSubLevel tiltSubLevel() { return tiltSubLevel; }
}
