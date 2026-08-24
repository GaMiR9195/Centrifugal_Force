package com.playsi.centrifugal_force.mixin;

import com.playsi.centrifugal_force.internal.AdhesionAccess;
import com.playsi.centrifugal_force.internal.AdhesionState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Adhesion state on the debug screen, appended where Sable appends its own lines. */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
    @ModifyVariable(method = "getSystemInformation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;showOnlyReducedInfo()Z", shift = At.Shift.BEFORE), ordinal = 0)
    public List<String> centrifugalForce$addDebugInfo(final List<String> lines) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return lines;

        final AdhesionState state = ((AdhesionAccess) player).centrifugalForce$peekAdhesionState();
        if (state == null || !state.isActive()) return lines;

        lines.add("");
        lines.add(ChatFormatting.UNDERLINE + "Centrifugal Force");
        lines.add("plane " + state.planeLabel() + ", tilt " + Math.round(state.tiltDegrees()) + "\u00b0");
        lines.add(state.isChangingPlane()
                ? "turning " + Math.round(state.transitionProgress() * 100.0) + "%"
                : state.isGrounded() ? "grounded" : "airborne");
        return lines;
    }
}
