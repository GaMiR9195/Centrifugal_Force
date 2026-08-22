package dev.gamir.sable_cf.client;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
import dev.gamir.sable_cf.physics.ForceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The numbers behind the arrows.
 *
 * <p>Arrows show you the shape of what is happening; only numbers tell you <i>why</i> you slid. Two
 * lines carry most of the value:</p>
 *
 * <ul>
 *   <li><b>press / hold / load.</b> Friction can hold {@code hold} and something is pushing with
 *       {@code load}, so the moment load passes hold you start moving - and you can watch it happen
 *       instead of guessing.</li>
 *   <li><b>share.</b> How much of the press the ride is supplying rather than gravity. This is the
 *       first thing to read when the mod seems to be doing something it should not: near zero means
 *       it believes nothing is acting on you, so anything moving you is not this mod.</li>
 * </ul>
 *
 * <p>Colours follow the same convention as {@code /sable_cf status}, so the same reading means the
 * same thing whichever surface you read it from.</p>
 */
public final class DebugHud {

    private static final int MARGIN = 6;
    private static final int LINE_HEIGHT = 10;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF7CE87C;
    private static final int AMBER = 0xFFFFC050;
    private static final int RED = 0xFFFF6060;
    private static final int GREY = 0xFFA0A0A0;
    private static final int VIOLET = 0xFFD08CFF;

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
            graphics.drawString(minecraft.font, "sable_cf idle (no sub-level)", MARGIN, y, GREY);
            return;
        }

        final double gravity = CfConfig.GRAVITY;
        final double spin = state.omega.length();

        graphics.drawString(minecraft.font, String.format(
                        "sable_cf spin %.2f rad/s (%.0f rpm) gate %.2f jolt %.1f rad/s2",
                        spin, spin * 60.0 / (2.0 * Math.PI), state.spinGate,
                        state.angularAcceleration.length()),
                MARGIN, y, WHITE);
        y += LINE_HEIGHT;

        graphics.drawString(minecraft.font, String.format(
                        "press %.2fg hold %.2fg load %.2fg",
                        state.press / gravity, state.hold / gravity, state.tangentialLoad / gravity),
                MARGIN, y, state.tangentialLoad > state.hold ? AMBER : GREEN);
        y += LINE_HEIGHT;

        // Share and contacts together answer "should this mod be doing anything at all right now".
        // Both near zero on an ordinary moving deck, by construction rather than by tuning.
        graphics.drawString(minecraft.font, String.format(
                        "share %.2f contacts %d %s",
                        state.frameShare,
                        state.contactCount,
                        state.attached ? "ATTACHED" : "free"),
                MARGIN, y, state.attached ? VIOLET : GREY);
        y += LINE_HEIGHT;

        // Air speed is deck-relative: the sub-level's rigid translation is subtracted, so a
        // platform merely travelling reads near zero here however fast it is going. "carried" is
        // what was taken out, shown so the subtraction is visible rather than a claim.
        graphics.drawString(minecraft.font, String.format(
                        "air %.1f deck %.1f carried %.1f own %.1f m/s drag %.2fg",
                        state.airVelocity.length(),
                        state.deckVelocity.length(),
                        state.deckTranslation.length(),
                        state.relativeVelocity.length(),
                        state.drag.length() / gravity),
                MARGIN, y, WHITE);
        y += LINE_HEIGHT;

        final String footing;
        final int footingColour;

        if (!state.gripped) {
            footing = "no footing";
            footingColour = RED;
        } else if (state.slipping) {
            footing = "sliding";
            footingColour = AMBER;
        } else {
            footing = "holding";
            footingColour = GREEN;
        }

        graphics.drawString(minecraft.font, String.format(
                        "%s%s%s tilt %.2f slip %.1f m/s",
                        footing,
                        state.wallRide ? " wall" : "",
                        state.bracing ? " braced" : "",
                        state.tilt,
                        state.slip.length()),
                MARGIN, y, footingColour);
        y += LINE_HEIGHT;

        graphics.drawString(minecraft.font, String.format(
                        "centrifugal %.2fg euler+linear %.2fg coriolis %.2fg applied %.2fg",
                        state.centrifugal.length() / gravity,
                        state.euler.length() / gravity,
                        state.coriolis.length() / gravity,
                        state.applied.length() / gravity),
                MARGIN, y, GREY);
        y += LINE_HEIGHT;

        graphics.drawString(minecraft.font, String.format(
                        "outward %.2fg climb %.2fg%s",
                        state.outwardSlip.length() / gravity,
                        state.climbAssist.length() / gravity,
                        state.released ? "  RELEASED" : ""),
                MARGIN, y, state.released ? VIOLET : GREY);
    }
}
