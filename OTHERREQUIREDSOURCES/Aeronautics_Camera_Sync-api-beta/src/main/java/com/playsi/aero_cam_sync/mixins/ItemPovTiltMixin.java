package com.playsi.aero_cam_sync.mixins;

import com.playsi.aero_cam_sync.TiltAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Приводит луч {@code Item#getPlayerPOVHitResult} в соответствие с наклонённой камерой.
 * Этот хелпер используют вёдра жидкости и многие предметы «использовать по блоку».
 *
 * <p>Чинит две вещи разом (в т.ч. для модовых вёдер/жидкостей, использующих этот хелпер):</p>
 * <ul>
 *   <li><b>Направление</b> — {@code calculateViewVector} наклоняется под поворот камеры,
 *       иначе жидкость ставилась бы мимо перекрестия.</li>
 *   <li><b>Начало луча</b> — {@code getEyePosition} сдвигается в позицию камеры, иначе луч
 *       идёт из реального глаза и попадает в блок под другой ГРАНЬЮ → жидкость ставится на
 *       соседний блок (баг с водой на наклонном сублевеле).</li>
 * </ul>
 *
 * <p>Общий миксин — работает и на клиенте (предсказание), и на сервере (авторитет).</p>
 */
@Mixin(Item.class)
public class ItemPovTiltMixin {

    // НАЧАЛО ЛУЧА здесь больше не правится (ветка experiment/clip-net): его должна забирать
    // сеть — ClipNetMixin. Этот путь заканчивается ванильным level.clip(), причём origin в нём
    // равен ровно player.getEyePosition(), то есть попадает под фильтр сети. Если после
    // проверки окажется, что не попадает — редирект надо вернуть.

    @Redirect(
            method = "getPlayerPOVHitResult",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;")
    )
    private static Vec3 aero$tiltPovLook(Player player, float pitch, float yaw) {
        Vec3 base = player.calculateViewVector(pitch, yaw);

        Quaternionf tilt = TiltAccess.getLookTilt(player);
        if (tilt == null) return base;

        Vector3f v = new Vector3f((float) base.x, (float) base.y, (float) base.z);
        tilt.transform(v);
        return new Vec3(v.x, v.y, v.z);
    }
}
