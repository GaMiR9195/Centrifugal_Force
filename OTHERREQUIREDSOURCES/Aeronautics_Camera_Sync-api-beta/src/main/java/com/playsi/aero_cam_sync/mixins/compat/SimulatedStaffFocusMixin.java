package com.playsi.aero_cam_sync.mixins.compat;

import com.playsi.aero_cam_sync.client.tilt.CameraController;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Начало луча посоха физики — из наклонённой камеры.
 *
 * <p>Кончик посоха {@code PhysicsStaffClientHandler#getStaffFocusPos} собирает так:</p>
 * <pre>
 *   PhysicsStaffItemRenderer.getFirstPersonFocusPos(pt)   // смещение кончика ОТНОСИТЕЛЬНО камеры
 *       .add(player.getPosition(pt))                      // ноги
 *       .add(0, Mth.lerp(pt, camera.eyeHeightOld, camera.eyeHeight), 0);
 * </pre>
 *
 * <p>То есть Simulated восстанавливает позицию камеры как «ноги плюс высота глаза» — и это
 * верно ровно до тех пор, пока камера не сдвинута. У нас она наклонена вокруг ног, поэтому
 * настоящая камера стоит в другой точке, а кончик посоха остаётся на ванильной. Отсюда
 * симптом: рамка выделения (её рисуют от {@code camera.getPosition()}) стоит верно, а луч
 * выходит не из посоха.</p>
 *
 * <p>Прибавляем ту же дельту, что и везде. Правим именно результат, а не слагаемые: первое
 * из них — смещение относительно камеры (учитывает FOV и поворот), второе и третье —
 * реконструкция её позиции, и портить сглаживание высоты глаза при приседании нельзя.</p>
 *
 * <p><b>Не путать с {@link SimulatedStaffBeamMixin}:</b> тот правит {@code dirToAnchor} —
 * то, КУДА повёрнута модель посоха в руке. Этот — ОТКУДА выходит луч. Симптомы похожи,
 * места разные, нужны оба.</p>
 *
 * <p>Ветка для чужих игроков и третьего лица ({@code isLocalPlayer()} / {@code isDetached()})
 * считает кончик от позиции тела, а не от камеры — там наша поправка неуместна.</p>
 *
 * <p><b>⚠ Первое лицо проверяется здесь ЯВНО (2026-08-16).</b> Раньше проверки не было: полагались
 * на то, что {@code aimEyeOffset} сам вернёт {@code null} вне первого лица. С тех пор третье лицо
 * стало API-переключателем, и это допущение перестало быть верным — мод, включивший третье лицо,
 * молча начал бы получать поправку в ветке, которая считает не от камеры. Условие ничего не
 * меняет по сравнению с прежним поведением; оно всего лишь перестало быть чужим побочным
 * свойством.</p>
 */
@Mixin(value = PhysicsStaffClientHandler.class, remap = false)
public abstract class SimulatedStaffFocusMixin {

    @Inject(method = "getStaffFocusPos", at = @At("RETURN"), cancellable = true)
    private static void aero$tiltFocusPos(Player player, boolean mainHand, float pt,
                                          CallbackInfoReturnable<Vec3> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player) return;
        // Явно, а не через побочное свойство aimEyeOffset — см. врезку в описании класса.
        if (!mc.options.getCameraType().isFirstPerson()) return;

        Vec3 offset = CameraController.aimEyeOffset(pt);
        if (offset == null) return;

        Vec3 focus = cir.getReturnValue();
        if (focus == null) return;

        cir.setReturnValue(focus.add(offset));
    }
}
