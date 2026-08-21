package com.playsi.aero_cam_sync.mixins.compat;

import com.llamalad7.mixinextras.sugar.Local;
import com.playsi.aero_cam_sync.TiltAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Вторая половина посоха физики: точка, КУДА он тянет объект.
 *
 * <p>Сервер держит цель как «глаз игрока плюс вектор взгляда на дистанцию удержания», причём
 * глаз собирает вручную ({@code PhysicsStaffServerHandler$DragSession#physicsTick}):</p>
 * <pre>
 *   eyePosY = Mth.lerp(partialTick, player.yOld, player.getY()) + player.getEyeHeight();
 *   localGoal.set(playerRelativeGoal).add(eyePosX, eyePosY, eyePosZ);
 * </pre>
 *
 * <p>{@code playerRelativeGoal} приходит с клиента и УЖЕ наклонён — это
 * {@code getLookAngle().scale(distance)}, а направление взгляда мы тилтим глобально. Точка же,
 * к которой он прибавляется, наклона не знает. Получается тот же гибрид, что у гаджетов Create:
 * объект висит не там, куда целишься, и промах растёт с креном.</p>
 *
 * <p>Прибавляем к цели дельту наклона. Считается через {@link TiltAccess} — на сервере тилт
 * приходит из {@code ServerTiltStore}, поэтому арифметика совпадает с клиентской.</p>
 *
 * <p>{@code @Pseudo} + {@code targets}: {@code DragSession} — приватный вложенный класс, обычным
 * {@code @Mixin(Class)} на него не сослаться, а сам Simulated едет внутри Create Aeronautics
 * через JarInJar и в компиляции у нас {@code compileOnly}.</p>
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession",
        remap = false)
public abstract class SimulatedStaffGoalMixin {

    @ModifyArgs(
            method = "physicsTick",
            at = @At(value = "INVOKE", target = "Lorg/joml/Vector3d;add(DDD)Lorg/joml/Vector3d;", remap = false),
            require = 0
    )
    private void aero$tiltDragGoal(Args args, @Local Player player) {
        Vec3 offset = TiltAccess.aimEyeOffset(player);
        if (offset == null) return;

        args.set(0, (double) args.get(0) + offset.x);
        args.set(1, (double) args.get(1) + offset.y);
        args.set(2, (double) args.get(2) + offset.z);
    }
}
