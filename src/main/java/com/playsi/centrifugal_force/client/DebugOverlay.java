package com.playsi.centrifugal_force.client;

import com.playsi.centrifugal_force.CentrifugalForce;
import com.playsi.centrifugal_force.internal.AdhesionAccess;
import com.playsi.centrifugal_force.internal.AdhesionState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = CentrifugalForce.MOD_ID, value = Dist.CLIENT)
public final class DebugOverlay {
    private static final int GRAY = 0xFFAAAAAA;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;

    private DebugOverlay() {}

    @SubscribeEvent
    public static void render(final RenderGuiEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) return;

        final AdhesionState state = ((AdhesionAccess) minecraft.player).centrifugalForce$peekAdhesionState();
        final boolean active = state != null && state.isActive();

        final List<Line> lines = new ArrayList<>();
        lines.add(line(part("Centrifugal Force", GRAY)));

        if (!active) {
            lines.add(line(part("Adhesion ", GRAY), part("IDLE", GRAY)));
        } else {
            lines.add(line(part("Adhesion ", GRAY), part("ACTIVE", GREEN)));
            lines.add(line(part("Plane ", GRAY), part(state.planeLabel(), WHITE)));
            lines.add(line(part("Tilt ", GRAY), part(round(state.tiltDegrees(), 1) + "\u00b0", WHITE)));
            lines.add(state.isChangingPlane()
                    ? line(part("Corner ", GRAY), part(Math.round(state.transitionProgress() * 100.0) + "%", WHITE))
                    : line(part("Corner ", GRAY), part("READY", GREEN)));
            final double support = state.supportDistance();
            lines.add(Double.isNaN(support)
                    ? line(part("Support ", GRAY), part("none", GRAY))
                    : line(part("Support ", GRAY), part(Double.toString(round(support, 2)), WHITE)));
            lines.add(line(part("Misses ", GRAY), part(Integer.toString(state.supportMissTicks()), WHITE)));
            lines.add(line(part("Frame ", GRAY), part("LIVE", GREEN)));
        }

        final GuiGraphics graphics = event.getGuiGraphics();
        final Font font = minecraft.font;
        final int right = graphics.guiWidth() - 6;
        int y = 6;
        for (final Line entry : lines) {
            int x = right - entry.width(font);
            for (final Part part : entry.parts()) {
                graphics.drawString(font, part.text(), x, y, part.color(), true);
                x += font.width(part.text());
            }
            y += font.lineHeight + 1;
        }
    }

    private static double round(final double value, final int digits) {
        final double scale = Math.pow(10.0, digits);
        return Math.round(value * scale) / scale;
    }

    private static Part part(final String text, final int color) {
        return new Part(text, color);
    }

    private static Line line(final Part... parts) {
        return new Line(List.of(parts));
    }

    private record Part(String text, int color) {}

    private record Line(List<Part> parts) {
        int width(final Font font) {
            int width = 0;
            for (final Part part : this.parts) width += font.width(part.text());
            return width;
        }
    }
}
