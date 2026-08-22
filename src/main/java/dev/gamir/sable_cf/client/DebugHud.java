package dev.gamir.sable_cf.client;

import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import dev.gamir.sable_cf.physics.ForceState;
import dev.gamir.sable_cf.physics.GravityPlane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The numbers behind the arrows.
 *
 * <p>Arrows show you the shape of what is happening; only numbers tell you <i>why</i> you slid.
 * Three lines carry most of the value:</p>
 *
 * <ul>
 *   <li><b>press / hold / load.</b> Friction can hold {@code hold} and something is pushing with
 *       {@code load}, so the moment load passes hold you start moving - and you can watch it happen
 *       instead of guessing.</li>
 *   <li><b>stick.</b> How much of a wall-walker you currently are, 0 to 1. This is the single
 *       number the hitbox, the camera and the force cancellation are all derived from, so if they
 *       ever seem to disagree, they are not disagreeing - this is what all three are reading.</li>
 *   <li><b>plane.</b> Which face has been committed to, and which one is challenging it and for how
 *       many ticks. A plane that flickers here is a plane that would have flickered the hitbox in
 *       the old build; now you can see the challenger being refused.</li>
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

    private static final String[] FACES = {"+Y", "-Y", "+X", "-X", "+Z", "-Z"};

    @SubscribeEvent
    public void onRenderGui(final RenderGuiEvent.Post event) {
        if (!CfConfig.SPEC.isLoaded() || !CfConfig.DEBUG_OVERLAY.get() || !CfConfig.DEBUG_TEXT.get()) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        if (!(minecraft.player instanceof BodyFrameHolder holder)) {
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrameOrNull();

        if (frame == null) {
            return;
        }

        final ForceState state = frame.state();
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
                        "press %.2fg ride %.2fg hold %.2fg load %.2fg",
                        state.press / gravity, state.ridePress / gravity,
                        state.hold / gravity, state.tangentialLoad / gravity),
                MARGIN, y, state.tangentialLoad > state.hold ? AMBER : GREEN);
        y += LINE_HEIGHT;

        // Stick and contacts together answer "should this mod be doing anything at all right now".
        // Both near zero on an ordinary moving deck, by construction rather than by tuning.
        graphics.drawString(minecraft.font, String.format(
                        "stick %.2f footing %.2f contacts %d plane %s%s",
                        state.stick,
                        state.footing,
                        state.contactCount,
                        face(state.planeIndex),
                        challenger(state)),
                MARGIN, y, state.stick > 0.5 ? VIOLET : GREY);
        y += LINE_HEIGHT;

        // Air speed is deck-relative: the sub-level's rigid translation is subtracted, so a
        // platform merely travelling reads near zero here however fast it is going. "carried" is
        // what was taken out, shown so the subtraction is visible rather than a claim.
        graphics.drawString(minecraft.font, String.format(
                        "air %.1f deck %.1f carried %.1f own %.1f m/s drag %.2fg",
                        state.airSpeed,
                        state.deckVelocity.length(),
                        state.deckTranslation.length(),
                        state.relativeVelocity.length(),
                        state.drag.length() / gravity),
                MARGIN, y, WHITE);
        y += LINE_HEIGHT;

        final String footing;
        final int footingColour;

        if (!state.gripped && state.slipping) {
            footing = "sliding";
            footingColour = AMBER;
        } else if (!state.gripped) {
            footing = "no footing";
            footingColour = RED;
        } else {
            footing = "holding";
            footingColour = GREEN;
        }

        graphics.drawString(minecraft.font, String.format(
                        "%s%s%s slip %.1f m/s2",
                        footing,
                        state.wallRide ? " wall" : "",
                        state.bracing ? " braced" : "",
                        state.slip.length()),
                MARGIN, y, footingColour);
        y += LINE_HEIGHT;

        graphics.drawString(minecraft.font, String.format(
                        "centrifugal %.2fg euler %.2fg linear %.2fg coriolis %.2fg applied %.2fg",
                        state.centrifugal.length() / gravity,
                        state.euler.length() / gravity,
                        state.linear.length() / gravity,
                        state.coriolis.length() / gravity,
                        state.applied.length() / gravity),
                MARGIN, y, GREY);
        y += LINE_HEIGHT;

        // Body and hitbox angles side by side: they should track each other. A gap that persists
        // means the hitbox could not fit and is waiting, which is the one case where the visual is
        // allowed to be ahead of the collision box.
        graphics.drawString(minecraft.font, String.format(
                        "body %.0f deg hitbox %.0f deg%s%s",
                        state.bodyAngleDeg,
                        state.hitboxAngleDeg,
                        state.clearanceBlocked ? "  BLOCKED" : "",
                        state.released ? "  RELEASED" : ""),
                MARGIN, y,
                state.clearanceBlocked ? AMBER : state.released ? VIOLET : GREY);
    }

    private static String face(final int index) {
        if (index < 0 || index >= FACES.length) {
            return "none";
        }

        return FACES[index];
    }

    private static String challenger(final ForceState state) {
        if (state.challengerIndex == GravityPlane.NONE) {
            return "";
        }

        return String.format(" <- %s x%d", face(state.challengerIndex), state.challengerTicks);
    }
}
