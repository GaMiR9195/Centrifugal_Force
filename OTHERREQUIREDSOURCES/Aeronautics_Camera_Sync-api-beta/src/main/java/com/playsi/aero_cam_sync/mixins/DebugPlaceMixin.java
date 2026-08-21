package com.playsi.aero_cam_sync.mixins;

import com.playsi.aero_cam_sync.AeroCamSync;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ВРЕМЕННАЯ ДИАГНОСТИКА (2026-08-03). Последнее звено цепочки: в какую клетку реально ляжет
 * блок. {@code getClickedPos()} = позиция хита, либо она же {@code .relative(грань)}, если
 * кликнутый блок не заменяем — то есть при верной позиции, но неверной грани блок уезжает
 * в соседнюю клетку. Удалить вместе с остальными Debug*-миксинами.
 */
@Mixin(BlockItem.class)
public abstract class DebugPlaceMixin {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"))
    private void aero$logPlace(BlockPlaceContext context, CallbackInfoReturnable<?> cir) {
        AeroCamSync.LOGGER.info(
                "[AeroCamSync] {} place: clickedPos={} face={} | horizDir={} rot={}",
                context.getLevel().isClientSide ? "CLIENT" : "SERVER",
                context.getClickedPos().toShortString(),
                context.getClickedFace(),
                context.getHorizontalDirection(),
                String.format("%.1f", context.getRotation()));
    }
}
