package com.playsi.aero_cam_sync.api;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A consistent snapshot of ACS state, taken by {@link AcsHandle#state}.
 *
 * <p>Works on both sides. The client-only half lives behind {@link #client()}.</p>
 *
 * <h2>The one rule worth remembering</h2>
 *
 * <p><b>The {@code aim*} values are never null and never garbage: when there is no tilt they
 * are equal to the {@code vanilla*} ones.</b> Write {@code state.aimRay(reach)} once and do not
 * branch on "what if there is no tilt today" — that branch is where the bugs live.</p>
 *
 * <p>{@code aim*} equals {@code vanilla*} when: the tilt is off in the config or by the
 * player's toggle, the player is in third person, the emergency legacy-pick fallback is on,
 * the tilt has been scaled to zero by a nearby wall, or the player is not on a sub-level.</p>
 *
 * <p>Exactly three things return {@code null}, and each means "there is no such thing", never
 * "there is no tilt": {@link #posTilt()}, {@link #lookTilt()} and {@link #client()}.</p>
 */
public interface AcsState {

    /** Whether ACS is enabled — both in the config and by the player's in-game toggle. */
    boolean modEnabled();

    /**
     * Whether a tilt is actually being applied right now.
     *
     * <p>Measured on the residual tilt, not on the settings: during the smooth ease-out after
     * {@link AcsHandle#suppress(long)} this stays {@code true} until the leftover tilt drops
     * below the threshold at which we stop correcting rays at all.</p>
     */
    boolean tiltApplied();

    /** Whether any mod currently holds a suppression lease. */
    boolean suppressed();

    /** Mod ids holding a suppression lease; empty if none. Order is not guaranteed. */
    List<String> suppressedBy();

    /**
     * The rotation applied to the camera <i>position</i> — the eye rotated around the feet —
     * or {@code null} when position shifting is not happening at all (the option is off, or
     * ACS is not tilting this player right now).
     *
     * <p>Identity while the tilt is on but the deck is level. This is the raw quaternion for
     * mods doing their own maths; for aiming, use {@link #aimEye()} and {@link #aimRay(double)},
     * which never need a null check.</p>
     */
    @Nullable Quaternionf posTilt();

    /**
     * The rotation applied to the view <i>direction</i>, or {@code null} when direction tilting
     * is not happening at all. Same shape as {@link #posTilt()}; the two are independent
     * options and either can be on alone.
     */
    @Nullable Quaternionf lookTilt();

    /** The player's untouched eye position, {@code player.getEyePosition(partialTick)}. */
    Vec3 vanillaEye();

    /** The point the player actually aims from — the eye plus the tilt correction. */
    Vec3 aimEye();

    /**
     * The untilted look direction, computed from the raw pitch and yaw.
     *
     * <p>Recomputed rather than stored, because it is a function of {@code partialTick} and you
     * may ask for any.</p>
     */
    Vec3 vanillaLook(float partialTick);

    /** The look direction as tilted by ACS — the one the crosshair points along. */
    Vec3 aimLook(float partialTick);

    /**
     * The ray a mod would have built without ACS: from {@link #vanillaEye()} along
     * {@link #vanillaLook} for {@code reach} blocks.
     */
    AcsRay vanillaRay(double reach);

    /**
     * The ray that matches what the player sees: from {@link #aimEye()} along {@link #aimLook}
     * for {@code reach} blocks. This is what belongs in your {@code ClipContext}.
     */
    AcsRay aimRay(double reach);

    /** The client-only half of the snapshot, or {@code null} on a dedicated server. */
    @Nullable AcsClientState client();
}
