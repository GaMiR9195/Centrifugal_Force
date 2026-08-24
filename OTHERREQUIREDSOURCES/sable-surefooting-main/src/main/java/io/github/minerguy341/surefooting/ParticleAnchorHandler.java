package io.github.minerguy341.surefooting;

import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Queue;

/**
 * Keeps particles anchored to the sub-level they are riding.
 * <p>
 * Sable already frame-locks particles perfectly while they "track" a sub-level, but kicks them
 * from tracking once they drift more than 0.5 blocks from the anchor point where tracking began
 * — handing them a linear, never-refreshed velocity snapshot. Rising smoke or flame drifts half
 * a block within a second and then streaks off tangentially, worse the faster the contraption
 * moves. Re-anchoring every tracked particle to its current position each tick keeps Sable's own
 * (correct) warp in charge for as long as the particle stays near the contraption; once it leaves
 * the exit margin we stop refreshing and Sable's stock kick sends it off with the local velocity,
 * which is the right behavior for departure.
 */
public final class ParticleAnchorHandler {

    /** {@code ParticleEngine#particles} — private; resolved once via reflection (mojmap runtime names). */
    private static Field particlesField;
    private static boolean reflectionFailed;

    @SubscribeEvent
    public void onClientTick(final ClientTickEvent.Post event) {
        if (reflectionFailed
                || Minecraft.getInstance().level == null
                || !SureFootingConfig.SPEC.isLoaded()
                || !SureFootingConfig.ENABLED.get()
                || !SureFootingConfig.ANCHOR_PARTICLES.get()) {
            return;
        }

        final Map<?, Queue<Particle>> particleMap = getParticleMap(Minecraft.getInstance().particleEngine);
        if (particleMap == null) {
            return;
        }

        for (final Queue<Particle> queue : particleMap.values()) {
            for (final Particle particle : queue) {
                this.reanchor(particle);
            }
        }
    }

    private void reanchor(final Particle particle) {
        final ParticleExtension extension = (ParticleExtension) particle;
        final SubLevel subLevel = extension.sable$getTrackingSubLevel();

        if (!(subLevel instanceof final ClientSubLevel clientSubLevel) || subLevel.isRemoved()) {
            return;
        }

        // Particles that opt out of kicking are already managed by Sable for their whole lifetime.
        if (particle instanceof final ParticleSubLevelKickable kickable && !kickable.sable$shouldKickFromTracking()) {
            return;
        }

        // Sable measures anchor drift against the particle's own position (Particle#x/y/z), not
        // against its bounding box centre. Vanilla builds the box sitting ON that position
        // (y == minY, see Particle#setPos), so anchoring the centre leaves a permanent bbHeight/2
        // residual instead of resetting drift to zero — and for a particle scaled to a height of
        // 1.0 or more that residual alone is the whole 0.5-block kick budget, so the refresh would
        // trigger the very kick it exists to prevent. x/y/z are protected, but the box gives them
        // back exactly.
        final AABB box = particle.getBoundingBox();
        final Vec3 pos = new Vec3((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
        final BoundingBox3dc bounds = subLevel.boundingBox();
        final double margin = SureFootingConfig.PARTICLE_EXIT_DISTANCE.get();

        final boolean within = pos.x >= bounds.minX() - margin && pos.x <= bounds.maxX() + margin
                && pos.y >= bounds.minY() - margin && pos.y <= bounds.maxY() + margin
                && pos.z >= bounds.minZ() - margin && pos.z <= bounds.maxZ() + margin;

        if (within) {
            // Refresh the anchor to the particle's current local position so Sable's 0.5-block
            // drift kick never fires while the particle stays with the contraption.
            extension.sable$setTrackingSubLevel(clientSubLevel, subLevel.logicalPose().transformPositionInverse(pos));
        }
        // Outside the margin: leave the stale anchor in place — Sable's kick fires on its own.
    }

    @SuppressWarnings("unchecked")
    private static Map<?, Queue<Particle>> getParticleMap(final ParticleEngine engine) {
        if (particlesField == null) {
            try {
                particlesField = ParticleEngine.class.getDeclaredField("particles");
                particlesField.setAccessible(true);
            } catch (final ReflectiveOperationException e) {
                reflectionFailed = true;
                SureFooting.LOGGER.error("Could not access ParticleEngine#particles; particle anchoring disabled", e);
                return null;
            }
        }

        try {
            return (Map<?, Queue<Particle>>) particlesField.get(engine);
        } catch (final IllegalAccessException e) {
            reflectionFailed = true;
            SureFooting.LOGGER.error("Could not read ParticleEngine#particles; particle anchoring disabled", e);
            return null;
        }
    }
}
