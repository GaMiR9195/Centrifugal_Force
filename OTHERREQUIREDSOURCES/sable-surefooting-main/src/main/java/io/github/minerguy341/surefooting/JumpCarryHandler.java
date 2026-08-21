package io.github.minerguy341.surefooting;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps the local player in a sub-level's reference frame through a jump/fall arc.
 * <p>
 * Sable carries entities standing on a sub-level by warping them with the sub-level's pose delta
 * each tick ("tracking"). Tracking is dropped as soon as no sub-level blocks are near the entity's
 * bounding box, which happens mid-jump; the fallback is a linear inherited-velocity snapshot that
 * cannot represent a rotating frame. The result is that jumping on a spinning platform drifts you
 * off it.
 * <p>
 * This handler watches for the tracking -> untracked transition while airborne and simply re-sets
 * the tracking sub-level each tick until the player lands (or bails out), so Sable's own warp keeps
 * carrying them. Sable clears the field again at the end of each tick; re-setting it once per tick
 * is cheap. Note this rides on Sable's {@code mixinterface} duck interfaces, which are marked
 * {@code @ApiStatus.Internal} — the dependency range is bounded accordingly.
 */
public final class JumpCarryHandler {

    private SubLevel lastTracked;
    private SubLevel carrying;
    private int carryTicks;

    /** The player this handler's state belongs to, so a respawn/dimension swap cannot inherit it. */
    private LocalPlayer carriedPlayer;

    /** Sub-level-local position when the carry started, for debug offset logging. */
    private Vec3 carryStartLocal;

    /** Last observed sub-level orientation, for rotating velocity with the frame. */
    private final FrameRotation.Anchor orientationAnchor = new FrameRotation.Anchor();

    @SubscribeEvent
    public void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null) {
            this.lastTracked = null;
            this.stopCarry(player, "left world");
            // Also release the anchor: it holds a ClientSubLevel, which holds its ClientLevel, so
            // leaving a world while on a contraption would pin that whole level (chunks, entities,
            // block entities) until the next world's first tick reset it.
            this.orientationAnchor.reset();
            this.carriedPlayer = null;
            return;
        }

        // A LocalPlayer is replaced on death/respawn and on dimension change while level and player
        // both stay non-null, so the checks above do not see it. Carrying state from the old player
        // must not be applied to the new one — it would warp a freshly spawned player by the
        // contraption's pose delta if they respawned inside its bounds.
        if (this.carriedPlayer != null && this.carriedPlayer != player) {
            this.lastTracked = null;
            this.stopCarry(null, "player replaced");
            this.orientationAnchor.reset();
        }
        this.carriedPlayer = player;

        if (!SureFootingConfig.SPEC.isLoaded() || !SureFootingConfig.ENABLED.get()) {
            this.lastTracked = null;
            this.stopCarry(player, "disabled");
            // Without this the anchor keeps a live orientation across the gap, and the first tick
            // after the config reloads applies the whole accumulated rotation as if it were one
            // tick's worth — a visible sideways yank on a spinning deck.
            this.orientationAnchor.reset();
            return;
        }

        SubLevel current = Sable.HELPER.getTrackingSubLevel(player);

        if (this.carrying != null) {
            if (current != null) {
                // Sable re-established tracking itself: the player landed on this (or another)
                // sub-level, and the stock behavior takes over again.
                this.stopCarry(player, current == this.carrying ? "landed on sub-level" : "tracked other sub-level");
            } else {
                final String stopReason = this.carryStopReason(player);

                if (stopReason != null) {
                    this.stopCarry(player, stopReason);
                } else {
                    ((EntityMovementExtension) player).sable$setTrackingSubLevel(this.carrying);
                    current = this.carrying;
                    this.carryTicks++;
                }
            }
        } else if (this.lastTracked != null && current == null && this.shouldStartCarry(player, this.lastTracked)) {
            this.carrying = this.lastTracked;
            this.carryTicks = 1; // this tick is the first carried one
            this.carryStartLocal = this.carrying.logicalPose().transformPositionInverse(player.position());

            ((EntityMovementExtension) player).sable$setTrackingSubLevel(this.carrying);
            current = this.carrying;

            if (SureFootingConfig.DEBUG_LOGGING.get()) {
                SureFooting.LOGGER.info("Carry start: sub-level {} local pos {}", this.carrying.getUniqueId(), this.carryStartLocal);
            }
        }

        this.rotateVelocityWithSubLevel(player, current);

        if (SureFootingConfig.DEBUG_LOGGING.get() && current != null) {
            final Vec3 movement = player.getDeltaMovement();
            // Airborne: always. Grounded: only when horizontal velocity is present, to catch
            // whatever injects tangential velocity into a standing player without spamming.
            if (!player.onGround() || movement.horizontalDistanceSqr() > 0.02 * 0.02) {
                this.logTelemetryTick(player, current);
            }
        }

        this.lastTracked = current;
    }

    /** Per-tick telemetry: shows where velocity/drift enters — input, Sable, or the carry. */
    private void logTelemetryTick(final LocalPlayer player, final SubLevel subLevel) {
        final Vec3 local = subLevel.logicalPose().transformPositionInverse(player.position());
        final Vec3 movement = player.getDeltaMovement();
        final org.joml.Vector3d inherited =
                ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension) player)
                        .sable$getInheritedVelocity();
        final String offset = this.carrying != null && this.carryStartLocal != null
                ? String.format("offset=(%.4f, %.4f, %.4f)", local.x - this.carryStartLocal.x, local.y - this.carryStartLocal.y, local.z - this.carryStartLocal.z)
                : String.format("local=(%.4f, %.4f, %.4f)", local.x, local.y, local.z);
        SureFooting.LOGGER.info(String.format(
                "[%s t=%d] %s dm=(%.4f, %.4f, %.4f) inhV=(%.4f, %.4f) input=(%.2f, %.2f) sprint=%b vColl=%b hColl=%b onGround=%b",
                player.onGround() ? "ground" : this.carrying != null ? "carry" : "tracked",
                this.carryTicks,
                offset,
                movement.x, movement.y, movement.z,
                inherited.x, inherited.z,
                player.input.forwardImpulse, player.input.leftImpulse,
                player.isSprinting(),
                player.verticalCollision,
                player.horizontalCollision,
                player.onGround()));
    }

    /**
     * While on or over a (possibly rotating) sub-level, rotate the player's own velocity by the
     * sub-level's per-tick orientation delta. The tracking warp carries the player's <em>position</em>
     * with the rotating frame, but their velocity would otherwise stay fixed in world space:
     * <ul>
     *   <li>Airborne, a jump aimed across a spinning platform lags ever further behind the
     *       platform's rotation. Rotating the velocity gives platform-frame ballistics: a jump
     *       aimed at a spot on the platform lands on that spot.</li>
     *   <li>Grounded, the momentum retained through friction points in a stale direction every
     *       tick, which sums to a sideways (Coriolis-like) pull when walking on a fast spinner.
     *       Rotating it keeps each step aimed where the player is steering.</li>
     * </ul>
     */
    private void rotateVelocityWithSubLevel(final LocalPlayer player, final SubLevel subLevel) {
        if (subLevel == null) {
            this.orientationAnchor.reset();
            return;
        }

        final boolean grounded = player.onGround();
        final boolean rotate = grounded
                ? SureFootingConfig.ROTATE_GROUND_VELOCITY.get()
                : SureFootingConfig.ROTATE_JUMP_VELOCITY.get();
        final double strength = !rotate ? 0.0
                : grounded
                ? SureFootingConfig.GROUND_ROTATION_STRENGTH.get()
                : SureFootingConfig.JUMP_ROTATION_STRENGTH.get();

        FrameRotation.rotateWithFrame(this.orientationAnchor, player, subLevel, strength, grounded);
    }

    private boolean shouldStartCarry(final LocalPlayer player, final SubLevel subLevel) {
        if (player.onGround() || player.verticalCollision) {
            // Tracking ended because the player landed (e.g. stepped onto vanilla ground), not
            // because they went airborne.
            return false;
        }

        return this.canCarry(player, subLevel);
    }

    /**
     * @return null to keep carrying, otherwise the reason to stop
     */
    private String carryStopReason(final LocalPlayer player) {
        // Deliberately NOT checking verticalCollision here: at high rotation rates Sable's substep
        // collision can report a vertical clip mid-arc without an actual landing, and releasing the
        // carry then hands the (large) tangential inherited velocity over as a straight-line fling.
        // A real landing sets onGround, either through vanilla or through Sable's collide.
        if (player.onGround()) {
            return "landed";
        }

        if (this.carryTicks >= SureFootingConfig.CARRY_TIMEOUT_TICKS.get()) {
            return "timed out";
        }

        if (!this.canCarry(player, this.carrying)) {
            return "left carry conditions";
        }

        return null;
    }

    /** Conditions shared by starting and continuing a carry. */
    private boolean canCarry(final LocalPlayer player, final SubLevel subLevel) {
        if (subLevel.isRemoved() || subLevel.getLevel() != player.level()) {
            return false;
        }

        if (player.isSpectator()
                || player.isPassenger()
                || player.getAbilities().flying
                || player.isFallFlying()
                || player.isInWater()
                || player.isInLava()) {
            return false;
        }

        final BoundingBox3dc bounds = subLevel.boundingBox();
        final double margin = SureFootingConfig.EXIT_DISTANCE.get();
        final Vec3 pos = player.position();

        return pos.x >= bounds.minX() - margin && pos.x <= bounds.maxX() + margin
                && pos.y >= bounds.minY() - margin && pos.y <= bounds.maxY() + margin
                && pos.z >= bounds.minZ() - margin && pos.z <= bounds.maxZ() + margin;
    }

    private void stopCarry(final LocalPlayer player, final String reason) {
        if (this.carrying == null) {
            return;
        }

        if (SureFootingConfig.SPEC.isLoaded() && SureFootingConfig.DEBUG_LOGGING.get()) {
            String offset = "n/a";
            if (player != null && this.carryStartLocal != null && !this.carrying.isRemoved()) {
                final Vec3 local = this.carrying.logicalPose().transformPositionInverse(player.position());
                final Vec3 delta = local.subtract(this.carryStartLocal);
                offset = String.format("(%.4f, %.4f, %.4f) horizontal %.4f", delta.x, delta.y, delta.z, delta.horizontalDistance());
            }
            SureFooting.LOGGER.info("Carry end after {} ticks ({}), local offset since start: {}", this.carryTicks, reason, offset);
        }

        this.carrying = null;
        this.carryTicks = 0;
        this.carryStartLocal = null;
    }
}
