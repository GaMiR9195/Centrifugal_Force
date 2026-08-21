package dev.gamir.sable_cf.client;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.compat.SableAccess;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
import dev.gamir.sable_cf.physics.ForceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3d;

/**
 * The numbers behind the arrows.
 *
 * <p>Arrows show you the shape of what is happening; only numbers tell you <i>why</i> you slid. The
 * line that matters is press vs hold vs load: friction can hold {@code hold} and something is
 * pushing with {@code load}, so the moment load passes hold you start moving, and you can watch it
 * happen instead of guessing.</p>
 */
public final class DebugHud {

    private static final int MARGIN = 6;
    private static final int LINE_HEIGHT = 10;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF7CE87C;
    private static final int AMBER = 0xFFFFC050;
    private static final int GREY = 0xFFA0A0A0;

    @SubscribeEvent
    public void onRenderGui(final RenderGuiEvent.Post event) {
        if (!CfConfig.SPEC.isLoaded() || !CfConfig.DEBUG_OVERLAY.get() || !CfConfig.DEBUG_TEXT.get()) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        final ForceState state = CentrifugalHandler.STATE;
        final GuiGraphics graphics = event.getGuiGraphics();

        int y = MARGIN;

        if (!state.active) {
            graphics.drawString(minecraft.font, "sable_cf  idle (no rotating sub-level)", MARGIN, y, GREY);
            return;
        }

        final double gravity = CfConfig.GRAVITY;
        final double omega = state.omega.length();

        graphics.drawString(minecraft.font, String.format(
                "sable_cf  spin %.2f rad/s  (%.1f rpm)", omega, omega * 60.0 / (2.0 * Math.PI)),
                MARGIN, y, WHITE);
        y += LINE_HEIGHT;

        graphics.drawString(minecraft.font, String.format(
                "press %.2f g   hold %.2f g   load %.2f g",
                state.press / gravity, state.hold / gravity, state.tangentialLoad / gravity),
                MARGIN, y, state.tangentialLoad > state.hold ? AMBER : GREEN);
        y += LINE_HEIGHT;

        graphics.drawString(minecraft.font, String.format(
                "air %.1f m/s   drag %.2f g   deck %.1f m/s",
                state.airVelocity.length(),
                state.drag.length() / gravity,
                state.deckVelocity.length()),
                MARGIN, y, WHITE);
        y += LINE_HEIGHT;

        final String footing;
        if (!state.gripped) {
            footing = "airborne";
        } else if (state.slipping) {
            footing = "slipping";
        } else {
            footing = "planted";
        }

        graphics.drawString(minecraft.font, String.format(
                "%s%s   centrifugal %.2f g   applied %.2f g",
                footing,
                state.bracing ? " (bracing)" : "",
                state.centrifugal.length() / gravity,
                state.applied.length() / gravity),
                MARGIN, y, state.gripped ? GREEN : AMBER);
        y += LINE_HEIGHT;

        // Sable's own handover velocity. Worth showing because it is the reason this mod does NOT
        // add the deck's momentum by hand when you are flung: Sable already did.
        final Vector3d inherited = SableAccess.inheritedVelocity(minecraft.player);

        if (inherited != null && inherited.lengthSquared() > 1.0e-6) {
            graphics.drawString(minecraft.font, String.format(
                    "sable inherited velocity %.1f m/s", inherited.length()), MARGIN, y, GREY);
        }
    }
}
