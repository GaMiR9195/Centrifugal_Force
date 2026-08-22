package dev.gamir.sable_cf.physics;

import dev.gamir.sable_cf.physics.BodyFrameHolder;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Drives {@link BodyFrame} for every player, on both sides.
 *
 * <p>{@code Pre} rather than {@code Post} on purpose. The body frame decides the shape of the
 * collision box, so it has to be up to date <i>before</i> the movement that will be tested against
 * that box. On {@code Post} the box would always be one tick behind the pose, which shows up as
 * catching on doorframes when a contraption is turning.</p>
 */
public final class BodyFrameTicker {

    @SubscribeEvent
    public void onPlayerTick(final PlayerTickEvent.Pre event) {
        final Player player = event.getEntity();

        if (!(player instanceof BodyFrameHolder holder)) {
            // Mixin did not apply. Nothing to do, and nothing to log every tick about.
            return;
        }

        final BodyFrame frame = holder.sable_cf$bodyFrame();

        frame.tick(player);

        // The refit is driven off the box being rebuilt, and that only happens when the entity
        // moves. A body that tilts while standing still would otherwise keep an upright box.
        player.refreshDimensions();
    }
}
