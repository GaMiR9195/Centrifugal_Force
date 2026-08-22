package dev.gamir.sable_cf.physics;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Drives {@link BodyFrame} for every player, on both sides.
 *
 * <p>{@code Pre} rather than {@code Post} on purpose. The body frame decides the orientation the
 * collision path will use, so it has to be up to date <i>before</i> the movement that will be
 * tested with it. On {@code Post} the orientation would always be one tick behind the pose, which
 * shows up as catching on doorframes when a contraption is turning.</p>
 *
 * <p>There is deliberately no {@code refreshDimensions()} call here. It existed to force vanilla to
 * rebuild the bounding box for the old AABB refit; with the refit gone there is no box to rebuild,
 * and calling it every tick for every player was pure cost - it also re-entered the size
 * calculation, which is not somewhere to be while deriving a body orientation.</p>
 */
public final class BodyFrameTicker {

    @SubscribeEvent
    public void onPlayerTick(final PlayerTickEvent.Pre event) {
        final Player player = event.getEntity();

        if (!(player instanceof BodyFrameHolder holder)) {
            // Mixin did not apply. Nothing to do, and nothing to log every tick about.
            return;
        }

        holder.sable_cf$bodyFrame().tick(player);
    }
}
