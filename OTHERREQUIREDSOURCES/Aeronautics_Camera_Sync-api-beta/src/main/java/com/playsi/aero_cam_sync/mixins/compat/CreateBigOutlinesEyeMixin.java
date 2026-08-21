package com.playsi.aero_cam_sync.mixins.compat;

import com.playsi.aero_cam_sync.client.tilt.ClientTiltAccess;
import com.playsi.aero_cam_sync.client.config.Config;
import com.simibubi.create.foundation.block.BigOutlines;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Совместимость с «большими обводками» Create: их собственный пик — от наклонённой камеры.
 *
 * <p><b>Что это.</b> {@code BigOutlines#pick()} — отдельный пик Create для блоков с крупной
 * обводкой (рельсы, трубы). Он не дополняет ванильный, а **перезаписывает
 * {@code mc.hitResult}** (в байткоде — {@code putfield Minecraft.hitResult} в конце метода).
 * То есть при наведении на рельсу финальный результат прицела считает Create, а не мы, и от
 * него зависит, куда встанет блок.</p>
 *
 * <p><b>Почему ломалось.</b> Луч там собран из двух источников, и правится только один:</p>
 * <pre>
 *   origin = player.getEyePosition(pt);                        // ванильный глаз — НИКЕМ не завёрнут
 *   target = RaycastHelper.getTraceTarget(player, d, origin);  // наклоняет наш CreateRaycastTiltMixin
 * </pre>
 * <p>Получается ровно тот гибрид, от которого мы ушли у себя (§1.6): начало ванильное,
 * направление наклонённое. Симптом — «целюсь в рельсу, блок встаёт не туда», и он исчезает
 * при выключенном «Сдвиге камеры», потому что тогда наш origin совпадает с ванильным.</p>
 *
 * <p><b>Почему Sable не покрыл.</b> Он для этого метода compat как раз имеет
 * ({@code compatibility/create/big_outlines_interaction/BigOutlinesMixin}), но чинит там
 * только саблевел-часть: метрику {@code distanceToSqr}, {@code rayTraceUntil} и
 * {@code VoxelShape#clip}. Точку глаза он не заворачивает — ему это и не нужно, у него
 * камера не наклонена.</p>
 *
 * <p>Лечится как у Cut Through (§1.5): направляем ванильный вызов в воронку Sable. Оттуда
 * приходит и правильная поза палубы, и наша наклонная поправка ({@code SableEyeMixin}).
 * Своей арифметики не нужно, и направление трогать не надо — оно уже наклонено.</p>
 *
 * <p><b>Почему сеть ({@code ClipNet}) его не заменяет — проверено экспериментом.</b> Этот путь
 * не зовёт {@code Level#clip} вообще: {@code RaycastHelper#rayTraceUntil} обходит блоки сам и
 * клипает {@code VoxelShape} напрямую. В ветке {@code experiment/clip-net} миксин был удалён
 * как контрольный случай — сеть его действительно не поймала, и симптом с рельсами вернулся.
 * Возвращён намеренно; см. {@code 2026-08-06-clip-net-experiment.md}.</p>
 *
 * <p>Под откатом {@code LEGACY_PICK} молчим: там пик целиком пересчитывает
 * {@code legacy.GameRendererPickMixin}, и поведение обязано остаться ровно как в 1.3.6.</p>
 */
@Mixin(value = BigOutlines.class, remap = false)
public abstract class CreateBigOutlinesEyeMixin {

    @Redirect(
            method = "pick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
                    remap = true),
            require = 0
    )
    private static Vec3 aero$sableEye(LocalPlayer player, float partialTick) {
        if (ClientTiltAccess.isLegacyPick()) {
            return player.getEyePosition(partialTick);
        }
        return Sable.HELPER.getEyePositionInterpolated(player, partialTick);
    }
}
