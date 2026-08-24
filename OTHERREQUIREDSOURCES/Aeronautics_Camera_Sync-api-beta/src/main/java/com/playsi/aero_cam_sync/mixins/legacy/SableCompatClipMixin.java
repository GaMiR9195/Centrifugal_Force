package com.playsi.aero_cam_sync.mixins.legacy;

import com.playsi.aero_cam_sync.client.config.Config;
import com.playsi.aero_cam_sync.client.legacy.ClipShifter;
import com.playsi.aero_cam_sync.client.tilt.CameraController;
import com.playsi.aero_cam_sync.client.camera.LevelClipMixinState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockGetter.class, priority = 1200)
public interface SableCompatClipMixin {

    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    default void shiftClipForCameraTilt(ClipContext context,
                                        CallbackInfoReturnable<BlockHitResult> cir) {
        // Сдвиг камеры — это сугубо логика главного (рендер) потока: камера-пик/взаимодействие.
        // Совместимость с Sound Physics Perfected: SPP трассирует звук, вызывая world.clip()
        // из ФОНОВОГО пула потоков (CompletableFuture.runAsync). Если влезть туда, мы (а) читаем
        // объекты главного потока (Minecraft/камера/игрок) из чужого потока и (б) гоняем общий
        // статический guard LevelClipMixinState из N потоков, повторно входя в clip Sable
        // (он не потокобезопасен) — отсюда наглухо зависший клиент при установке/ломке блока
        // на сублевеле. Лучи SPP — не камера, их трогать нельзя: на не-главном потоке выходим.
        if (!Minecraft.getInstance().isSameThread()) return;
        if (!Config.isLoaded()) return;
        // Этот хук сдвигает ЛЮБОЙ клип, чей старт ближе 4 блоков к игроку, — включая чужую
        // физику (Issue #30, Bits'n'Tracks). В новом пути начало луча задаётся один раз,
        // в PickScope, поэтому здесь не сдвигаем ничего. Остаётся только под явным откатом.
        if (!Config.LEGACY_PICK.get()) return;
        if (LevelClipMixinState.inTiltedClip) return;
        if (!Config.MOD_ENABLED.get()) return;
        if (!CameraController.shouldApplyTilt()) return;
        if (!Config.MODIFY_CAMERA_POS.get()) return;

        try {
            ClipShifter.tryShift((BlockGetter) this, context, cir);
        } catch (Throwable ignored) { }
    }
}