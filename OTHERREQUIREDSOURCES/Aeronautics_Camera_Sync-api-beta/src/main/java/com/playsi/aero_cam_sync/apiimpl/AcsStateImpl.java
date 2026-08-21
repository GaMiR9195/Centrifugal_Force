package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.TiltAccess;
import com.playsi.aero_cam_sync.api.AcsClientState;
import com.playsi.aero_cam_sync.api.AcsRay;
import com.playsi.aero_cam_sync.api.AcsState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Снимок состояния ACS.
 *
 * <h2>Инвариант: чистое чтение</h2>
 *
 * <p>Ни одного клипа, ни одной записи в общее состояние. Мод будет звать {@code state()} каждый
 * кадр, и снимок, который что-то меняет, станет источником багов, неотличимых от наших
 * собственных.</p>
 *
 * <h2>Сторона</h2>
 *
 * <p>Все клиентские ссылки исполняются только внутри ветки {@code isClientSide} — тот же приём,
 * что в {@code TiltAccess}. Клиентская половина собирается статической фабрикой, объявленной
 * как возвращающая ИНТЕРФЕЙС, чтобы на сервере класс с полем {@code ClientSubLevel} вообще не
 * грузился.</p>
 */
final class AcsStateImpl implements AcsState {

    /** Чей это снимок — для сводки обращений. {@code null} — снимок наш собственный, для событий. */
    private final @Nullable AcsHandleImpl handle;
    private final Player player;
    private final float partialTick;

    private final boolean modEnabled;
    private final Vec3 vanillaEye;
    private final Vec3 aimEye;

    /** Поворот, применяемый к НАПРАВЛЕНИЮ взгляда, либо {@code null} — направление ванильное. */
    private final @Nullable Quaternionf aimRotation;

    private final @Nullable Quaternionf posTilt;
    private final @Nullable Quaternionf lookTilt;
    private final boolean tiltApplied;
    private final boolean suppressed;
    private final List<String> suppressedBy;
    private final @Nullable AcsClientState client;

    static AcsState capture(@Nullable AcsHandleImpl handle, Player player, float partialTick) {
        boolean clientSide = player.level().isClientSide;
        return new AcsStateImpl(handle, player, partialTick, clientSide);
    }

    private AcsStateImpl(@Nullable AcsHandleImpl handle, Player player, float partialTick,
                         boolean clientSide) {
        this.handle = handle;
        this.player = player;
        this.partialTick = partialTick;

        // Под аварийным откатом прицел правит старый глобальный сдвиг, а не мы: наклонённые
        // поля обязаны совпасть с ванильными, иначе мод посчитает поправку второй раз.
        boolean legacy = clientSide
                && com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isLegacyPick();

        this.modEnabled = clientSide
                ? com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isModEnabled()
                : TiltAccess.getLookTilt(player) != null || TiltAccess.getPosTilt(player) != null;

        this.posTilt = TiltAccess.getPosTilt(player);
        this.lookTilt = TiltAccess.getLookTilt(player);

        this.vanillaEye = clientSide ? player.getEyePosition(partialTick) : player.getEyePosition();

        Vec3 offset = legacy ? null : eyeOffset(player, partialTick, clientSide);
        this.aimEye = offset == null ? vanillaEye : vanillaEye.add(offset);
        this.aimRotation = legacy ? null : lookTilt;

        this.tiltApplied = isTiltApplied();

        this.suppressed = SuppressionLeases.isSuppressed();
        this.suppressedBy = SuppressionLeases.holders();

        this.client = clientSide ? AcsClientStateImpl.capture() : null;
    }

    /**
     * Насколько наклон отодвигает глаз. На клиенте — версия с {@code partialTick} и теми же
     * гейтами, что у воронки и у сети ({@code isAimShiftAllowed}): три источника одной поправки
     * обязаны включаться и выключаться синхронно (§1.8, инвариант 2). Побочный эффект, который
     * так и задуман: снимок, взятый внутри {@code withVanillaEye}, отдаёт ванильный глаз.
     */
    private static @Nullable Vec3 eyeOffset(Player player, float partialTick, boolean clientSide) {
        if (!clientSide) return TiltAccess.aimEyeOffset(player);
        if (!com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess.isAimShiftAllowed()) return null;
        return com.playsi.aero_cam_sync.client.tilt.CameraController.aimEyeOffset(partialTick);
    }

    /**
     * Наклон применяется, если значима хотя бы одна половина поправки — сдвиг начала или
     * поворот направления. Обе меряются длиной смещения, поэтому порог один и тот же, тот же,
     * что у сети ({@link TiltAccess#EPSILON_SQR}).
     *
     * <p>Считается по ФАКТУ, а не по настройкам: во время плавного увода после
     * {@code suppress()} камера ещё наклонена, и здесь честно {@code true}.</p>
     */
    private boolean isTiltApplied() {
        if (aimEye.distanceToSqr(vanillaEye) >= TiltAccess.EPSILON_SQR) return true;
        if (aimRotation == null) return false;
        return aimLook(partialTick).distanceToSqr(vanillaLook(partialTick)) >= TiltAccess.EPSILON_SQR;
    }

    // ------------------------------------------------------------------ AcsState

    @Override public boolean modEnabled() { return modEnabled; }
    @Override public boolean tiltApplied() { return tiltApplied; }
    @Override public boolean suppressed() { return suppressed; }
    @Override public List<String> suppressedBy() { return suppressedBy; }

    @Override public @Nullable Quaternionf posTilt() {
        return posTilt == null ? null : new Quaternionf(posTilt);
    }

    @Override public @Nullable Quaternionf lookTilt() {
        return lookTilt == null ? null : new Quaternionf(lookTilt);
    }

    @Override public Vec3 vanillaEye() { return vanillaEye; }
    @Override public Vec3 aimEye() { return aimEye; }

    @Override public Vec3 vanillaLook(float partialTick) {
        // С нуля из сырых xRot/yRot, а не инверсией кватерниона: направление — функция от
        // partialTick, мод может спросить произвольный, а сохранённого значения на него нет.
        return player.calculateViewVector(
                player.getViewXRot(partialTick), player.getViewYRot(partialTick));
    }

    @Override public Vec3 aimLook(float partialTick) {
        Vec3 vanilla = vanillaLook(partialTick);
        if (aimRotation == null) return vanilla;
        Vector3f v = new Vector3f((float) vanilla.x, (float) vanilla.y, (float) vanilla.z);
        aimRotation.transform(v);
        return new Vec3(v.x, v.y, v.z);
    }

    @Override public AcsRay vanillaRay(double reach) {
        return AcsRayImpl.of(vanillaEye, vanillaLook(partialTick), reach);
    }

    @Override public AcsRay aimRay(double reach) {
        if (handle != null) ApiLog.count(handle, AcsHandleImpl.Call.AIM_RAY);
        return AcsRayImpl.of(aimEye, aimLook(partialTick), reach);
    }

    @Override public @Nullable AcsClientState client() { return client; }
}
