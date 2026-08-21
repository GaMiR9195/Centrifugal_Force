package com.playsi.aero_cam_sync.mixins;

import com.playsi.aero_cam_sync.TiltAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Наклон направления взгляда — ось «куда смотрит игрок», обе стороны сразу.
 *
 * <p>Был двумя классами-близнецами: {@code client.EntityLookMixin} и
 * {@code ServerEntityLookMixin}, те же два инжекта в те же два метода {@link Entity},
 * различались только источником кватерниона. Само это ветвление давно живёт в
 * {@link TiltAccess#getLookTilt(Player)} — на клиенте тилт берётся у камеры, на сервере из
 * {@code ServerTiltStore}, — поэтому двум классам было нечего разделять.</p>
 *
 * <p><b>Миксин ОБЩИЙ, и это обязательно.</b> Он должен работать и на выделенном сервере, где
 * авторитетны серверные расчёты. Клиентских типов здесь нет ни одного: до клиентского пакета
 * дотягивается только {@code TiltAccess}, и только внутри ветки {@code level().isClientSide} —
 * тот же приём, что в {@code SideGate} и {@code AcsStateImpl}. Нарушение этого правила роняет
 * выделенный сервер на хендшейке (Issue #33).</p>
 *
 * <p><b>Приоритет 1300 достался от клиентской половины.</b> У серверной был умолчательный 1000.
 * Оба инжекта сидят на RETURN и только правят возвращаемое значение, так что порядок важен лишь
 * там, где направление взгляда правит ещё чей-то миксин; на сервере такое соседство и есть то,
 * что стоит проверить живьём.</p>
 */
@Mixin(value = Entity.class, priority = 1300)
public abstract class EntityLookMixin {

    @Inject(method = "getViewVector", at = @At("RETURN"), cancellable = true)
    private void aero$tiltViewVector(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        aero$applyLookTilt(cir);
    }

    @Inject(method = "getLookAngle", at = @At("RETURN"), cancellable = true)
    private void aero$tiltLookAngle(CallbackInfoReturnable<Vec3> cir) {
        aero$applyLookTilt(cir);
    }

    /**
     * Гейты не проверяются здесь намеренно: их все держит {@link TiltAccess#getLookTilt(Player)}
     * — включённость мода, {@code MODIFY_CAMERA_ROT}, {@code shouldApplyTilt()} и то, что игрок
     * локальный, на клиенте; наличие присланного кватерниона на сервере. {@code null} оттуда и
     * значит «не вмешиваемся».
     */
    @Unique
    private void aero$applyLookTilt(CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) this instanceof Player player)) return;

        Quaternionf tilt = TiltAccess.getLookTilt(player);
        if (tilt == null) return;

        Vec3 vanilla = cir.getReturnValue();
        Vector3f v = new Vector3f((float) vanilla.x, (float) vanilla.y, (float) vanilla.z);
        tilt.transform(v);
        cir.setReturnValue(new Vec3(v.x, v.y, v.z));
    }
}
