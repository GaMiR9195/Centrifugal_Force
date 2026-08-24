package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.AimQuery;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Факты об одном луче для {@code AimPolicy}.
 *
 * <p>Собирается ТОЛЬКО когда политики зарегистрированы: {@code decide} зовётся из {@code clip}
 * десятки раз за кадр, и обычный случай — политик нет — обязан стоить одно сравнение и ни одной
 * аллокации.</p>
 */
record AimQueryImpl(Player player, Vec3 from, Vec3 to,
                    @Nullable ClipContext context, boolean startsAtEye) implements AimQuery {
}
