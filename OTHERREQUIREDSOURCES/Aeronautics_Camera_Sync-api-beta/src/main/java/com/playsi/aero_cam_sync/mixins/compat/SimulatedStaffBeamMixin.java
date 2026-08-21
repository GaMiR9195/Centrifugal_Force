package com.playsi.aero_cam_sync.mixins.compat;

import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Совместимость с посохом физики (Create Simulated): луч посоха выходит из наклонённой камеры.
 *
 * <p>Симптом: выделение схваченного объекта стоит верно, а сам луч уходит вбок — как будто
 * камера не наклонена. Так и есть: рамку выделения рисует
 * {@code PhysicsStaffRenderHandler} от {@code camera.getPosition()} (то есть уже от нашей
 * камеры), а направление луча {@code PhysicsStaffItemRenderer} считает от ванильного
 * {@code Entity#getEyePosition(F)}:</p>
 * <pre>
 *   dirToAnchor = globalAnchor.sub(player.getEyePosition(partialTicks)).normalize();
 * </pre>
 *
 * <p>Заворачиваем этот вызов в воронку Sable. Она и сама по себе правильнее (собирает глаз
 * против позы палубы), и через неё приходит наша наклонная поправка — {@code SableEyeMixin}
 * добавляет её на выходе. Своей арифметики тут не нужно вовсе.</p>
 */
@Mixin(value = PhysicsStaffItemRenderer.class, remap = false)
public abstract class SimulatedStaffBeamMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
                    remap = true),
            require = 0
    )
    private Vec3 aero$sableEye(Player player, float partialTick) {
        return Sable.HELPER.getEyePositionInterpolated(player, partialTick);
    }
}
