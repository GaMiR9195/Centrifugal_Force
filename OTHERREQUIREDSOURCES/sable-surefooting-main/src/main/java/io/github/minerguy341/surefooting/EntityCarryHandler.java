package io.github.minerguy341.surefooting;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Server-side counterpart of {@link JumpCarryHandler} for non-player entities.
 * <p>
 * Sable's collision passes an inherited velocity only for living entities — item drops, XP orbs
 * and other non-living entities get {@code Vec3.ZERO}, i.e. no airborne carry at all — and every
 * non-player entity loses tracking the moment it leaves the sub-level surface, exactly like
 * players did before this mod. Items even spawn with a random upward pop, so on a fast deck they
 * often never establish tracking in the first place.
 * <p>
 * This handler engages tracking for items dropped over a sub-level, keeps entities tracked through
 * airborne arcs (so Sable's warp carries them), and rotates their velocity with the sub-level's
 * frame. Players are handled client-side; projectiles are excluded because Sable already applies
 * launch velocity and frame-locking would bend their trajectories.
 */
public final class EntityCarryHandler {

    private static final class CarryState {
        SubLevel lastTracked;
        SubLevel carrying;
        int carryTicks;
        final FrameRotation.Anchor orientationAnchor = new FrameRotation.Anchor();
    }

    /** Fallback exit distance for the window before the server config has loaded. */
    private static final double DEFAULT_EXIT_DISTANCE = 4.0;

    private final Map<Entity, CarryState> states = new WeakHashMap<>();

    /** Items dropped over a contraption, awaiting first-tick tracking (see {@link #onEntityJoin}). */
    private final Map<Entity, SubLevel> pendingItemTrack = new WeakHashMap<>();

    @SubscribeEvent
    public void onEntityJoin(final EntityJoinLevelEvent event) {
        final Entity entity = event.getEntity();

        // loadedFromDisk: only genuinely spawning items are candidates. A chunk load re-fires this
        // event for every item already lying in it, and ItemEntity persists its thrower UUID
        // (re-resolved lazily by getOwner), so a disk-loaded stack whose thrower happens to be
        // riding a contraption would otherwise be pinned to a deck it was never on.
        if (event.getLevel().isClientSide || event.loadedFromDisk() || !this.isConfigOn()
                || !(entity instanceof final ItemEntity item) || !this.isEligible(entity)) {
            return;
        }

        // Items dropped by someone riding a contraption spawn with the dropper's throw velocity but
        // none of the contraption's motion. A velocity seed cannot hold an item on a spinning deck —
        // it flies straight (tangent) while the deck curves away, so it always lags behind the
        // rotation. Instead, track the item so Sable's warp carries it around the curve, like the
        // player. Tracking must NOT be set here at spawn (that flips the item's add-packet into the
        // relative frame and misplaces it client-side); we record it and engage tracking on its
        // first tick, once the global spawn packet has gone out.
        final Entity owner = item.getOwner();
        if (owner == null) {
            return;
        }

        final SubLevel ownerSubLevel = Sable.HELPER.getTrackingSubLevel(owner);
        if (ownerSubLevel == null || ownerSubLevel.isRemoved() || ownerSubLevel.getLevel() != item.level()) {
            return;
        }

        // The owner riding a contraption does not make THIS item's position meaningful on it. An
        // item that is not actually on the deck must not be pinned to it: tracking warps position
        // by the sub-level's pose delta, so a distant item would be dragged bodily through the
        // world every tick.
        if (!this.withinBounds(item, ownerSubLevel)) {
            return;
        }

        this.pendingItemTrack.put(item, ownerSubLevel);
    }

    @SubscribeEvent
    public void onEntityTickPre(final EntityTickEvent.Pre event) {
        final Entity entity = event.getEntity();

        if (entity.level().isClientSide || !this.isConfigOn() || !this.isEligible(entity)) {
            return;
        }

        // Engage deferred tracking for freshly dropped items (see onEntityJoin) BEFORE their first
        // movement tick. By now the spawn packet has gone out in world coordinates (so networking
        // isn't disturbed), and pinning the item to the sub-level here — while it still sits at the
        // exact drop point — captures the correct local position. Engaging in Post instead would
        // let the item drift one world-frame tick first, offsetting the landing by a full tick of
        // platform rotation (invisible at low RPM, blocks of error at extreme speeds).
        final SubLevel pending = this.pendingItemTrack.remove(entity);
        if (pending == null || pending.isRemoved() || pending.getLevel() != entity.level()
                || !this.withinBounds(entity, pending)) {
            return;
        }

        final EntityMovementExtension extension = (EntityMovementExtension) entity;
        if (extension.sable$getTrackingSubLevel() == null && this.states.get(entity) == null) {
            final CarryState state = new CarryState();
            state.carrying = pending;
            state.carryTicks = 1; // this tick is the first carried one
            this.states.put(entity, state);
            extension.sable$setTrackingSubLevel(pending);
        }
    }

    @SubscribeEvent
    public void onEntityTick(final EntityTickEvent.Post event) {
        final Entity entity = event.getEntity();

        if (entity.level().isClientSide) {
            return;
        }

        // Eligibility can flip mid-carry (a blacklist edit, or the entity mounting something).
        // Drop any state rather than returning past the cleanup: a stale entry would otherwise sit
        // there with its carrying/carryTicks frozen and resume from it if eligibility came back.
        if (!this.isEligible(entity)) {
            this.states.remove(entity);
            this.pendingItemTrack.remove(entity);
            return;
        }

        final EntityMovementExtension extension = (EntityMovementExtension) entity;
        SubLevel current = extension.sable$getTrackingSubLevel();

        if (!this.isConfigOn()) {
            this.states.remove(entity);
            this.pendingItemTrack.remove(entity);
            return;
        }

        CarryState state = this.states.get(entity);
        if (current == null && state == null) {
            return; // fast path: entity has nothing to do with sub-levels
        }

        if (state == null) {
            state = new CarryState();
            this.states.put(entity, state);
        }

        final boolean isItem = entity instanceof ItemEntity;

        if (state.carrying != null) {
            // Non-living entities (items, XP) are not held by Sable's own tracking on a rotating
            // deck — handing off (as we do for mobs, which Sable keeps) leaves them world-fixed
            // while the platform turns under them, so they visibly drag against the spin. Keep
            // re-asserting tracking every tick for items, on the ground included, until they leave
            // the sub-level's bounds; only mobs hand back to Sable once they land.
            if (!isItem && current != null) {
                state.carrying = null; // Sable re-established tracking itself (landed)
            } else if (this.shouldStopCarry(entity, state)) {
                state.carrying = null;
            } else {
                extension.sable$setTrackingSubLevel(state.carrying);
                current = state.carrying;
                state.carryTicks++;
            }
        } else if (state.lastTracked != null && current == null && this.shouldStartCarry(entity, state.lastTracked)) {
            state.carrying = state.lastTracked;
            state.carryTicks = 1; // this tick is the first carried one
            extension.sable$setTrackingSubLevel(state.carrying);
            current = state.carrying;
        }

        if (current != null) {
            final boolean grounded = entity.onGround();
            final double strength = grounded
                    ? SureFootingServerConfig.ENTITY_GROUND_ROTATION_STRENGTH.get()
                    : SureFootingServerConfig.ENTITY_JUMP_ROTATION_STRENGTH.get();
            // Item yaw is meaningless (their visual spin is a client render animation); everything
            // else turns with the frame so it keeps facing the same way relative to the deck.
            final boolean rotateYaw = !isItem && SureFootingServerConfig.ROTATE_ENTITY_YAW.get();
            FrameRotation.rotateWithFrame(state.orientationAnchor, entity, current, strength, grounded, rotateYaw);
        } else {
            state.orientationAnchor.reset();
        }

        state.lastTracked = current;

        if (current == null && state.carrying == null) {
            this.states.remove(entity); // nothing left to remember
        }
    }

    /**
     * Drops all carry state when a level unloads.
     * <p>
     * {@link CarryState} holds {@link SubLevel}s, and a {@code SubLevel} holds its {@code Level},
     * which holds every entity in it — including the very entity used as the weak key here. That
     * cycle makes the key strongly reachable, so {@link WeakHashMap} can never expunge the entry
     * and the whole unloaded level is retained. {@link #pendingItemTrack} holds a {@code SubLevel}
     * directly and has the same problem. Clearing on unload breaks both. Clearing everything rather
     * than filtering by level is deliberate: the state is per-tick and rebuilds itself immediately
     * for any entity still being carried in a level that is staying.
     */
    @SubscribeEvent
    public void onLevelUnload(final LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            this.states.clear();
            this.pendingItemTrack.clear();
        }
    }

    private boolean isConfigOn() {
        return SureFootingServerConfig.SPEC.isLoaded() && SureFootingServerConfig.CARRY_ENTITIES.get();
    }

    private boolean isEligible(final Entity entity) {
        if (entity instanceof Player || entity instanceof Projectile || entity.isPassenger()) {
            return false;
        }

        return !SureFootingServerConfig.SPEC.isLoaded()
                || SureFootingServerConfig.CARRY_BLACKLIST.get().isEmpty()
                || !SureFootingServerConfig.CARRY_BLACKLIST.get().contains(EntityType.getKey(entity.getType()).toString());
    }

    private boolean shouldStartCarry(final Entity entity, final SubLevel subLevel) {
        if (entity.onGround() || entity.verticalCollision) {
            return false; // tracking ended by landing, not by going airborne
        }

        return this.canCarry(entity, subLevel);
    }

    private boolean shouldStopCarry(final Entity entity, final CarryState state) {
        if (!this.canCarry(entity, state.carrying)) {
            return true; // left the deck's bounds, entered water/lava, or the sub-level is gone
        }

        // Items ride the deck indefinitely while within bounds (they rest on it and must keep
        // rotating with it); mobs hand back to Sable on landing and time out of the airborne carry.
        if (entity instanceof ItemEntity) {
            return false;
        }

        return entity.onGround() || state.carryTicks >= SureFootingServerConfig.CARRY_TIMEOUT_TICKS.get();
    }

    private boolean canCarry(final Entity entity, final SubLevel subLevel) {
        if (subLevel.isRemoved() || subLevel.getLevel() != entity.level()) {
            return false;
        }

        if (entity.isInWater() || entity.isInLava()
                || (entity instanceof final LivingEntity living && living.isFallFlying())) {
            return false;
        }

        return this.withinBounds(entity, subLevel);
    }

    /** Whether the entity is inside the sub-level's world-space bounds, plus its exit margin. */
    private boolean withinBounds(final Entity entity, final SubLevel subLevel) {
        final BoundingBox3dc bounds = subLevel.boundingBox();
        final double margin = this.exitDistanceFor(entity.getType());
        final Vec3 pos = entity.position();

        return pos.x >= bounds.minX() - margin && pos.x <= bounds.maxX() + margin
                && pos.y >= bounds.minY() - margin && pos.y <= bounds.maxY() + margin
                && pos.z >= bounds.minZ() - margin && pos.z <= bounds.maxZ() + margin;
    }

    /** Cache of parsed per-type exit distances, rebuilt when the config list reference changes. */
    private List<? extends String> cachedOverrideList;
    private Map<EntityType<?>, Double> exitDistanceOverrides = Map.of();

    private double exitDistanceFor(final EntityType<?> type) {
        if (!SureFootingServerConfig.SPEC.isLoaded()) {
            return DEFAULT_EXIT_DISTANCE;
        }

        final List<? extends String> list = SureFootingServerConfig.EXIT_DISTANCE_OVERRIDES.get();
        if (list != this.cachedOverrideList) {
            this.cachedOverrideList = list;
            this.exitDistanceOverrides = parseOverrides(list);
        }

        final Double override = this.exitDistanceOverrides.get(type);
        return override != null ? override : SureFootingServerConfig.EXIT_DISTANCE.get();
    }

    private static Map<EntityType<?>, Double> parseOverrides(final List<? extends String> list) {
        final Map<EntityType<?>, Double> map = new HashMap<>();

        for (final String entry : list) {
            final int eq = entry.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }

            final ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, eq).trim());
            if (id == null) {
                continue;
            }

            final EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (type == null) {
                continue;
            }

            try {
                map.put(type, Double.parseDouble(entry.substring(eq + 1).trim()));
            } catch (final NumberFormatException ignored) {
                // validated at config load; skip defensively
            }
        }

        return map;
    }
}
