package com.playsi.aero_cam_sync.api;

import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * Everything a {@link TiltSource} is handed to decide with, and everything it is allowed to
 * read while deciding.
 *
 * <h2>Why a context object and not {@link AcsState}</h2>
 *
 * <p><b>Do not call {@link AcsHandle#state} from inside a source.</b> The snapshot reports the
 * resolved tilt, resolving asks the sources, and a source asking for the snapshot closes that
 * loop — a {@code StackOverflowError}, not a wrong number. This object exists so there is
 * never a reason to: it carries the same inputs, already computed, plus
 * {@link #acsTilt()} — the answer ACS arrived at before anyone was asked.</p>
 *
 * <h2>Lifetime</h2>
 *
 * <p>Valid only for the duration of the call it was passed to. Values are already interpolated
 * for {@link #partialTick()}; the quaternions and vectors are yours to keep, they are copies.</p>
 */
public interface TiltContext {

    /** The local player the tilt is being computed for. Never null. */
    Player player();

    /** Render partial tick of the frame being drawn. */
    float partialTick();

    /**
     * Length of this frame in ticks — {@code getRealtimeDeltaTicks()}.
     *
     * <p>This is the number to smooth against. A source that eases towards a target must do it
     * off frame time, exactly as ACS does, or it will move at a different speed on a different
     * machine.</p>
     */
    float deltaTicks();

    /**
     * The surface normal under the player, in world space, or {@code null} when there is no
     * surface to speak of — the player is not on a sub-level, is in the air, or the ray missed.
     *
     * <p>This is the raw input ACS computed its own tilt from, handed over so a source does not
     * have to cast the same ray again. Two mods raycasting the same floor with slightly
     * different thresholds is the inconsistency this whole mechanism exists to remove.</p>
     */
    @Nullable Vector3f surfaceNormal();

    /**
     * The tilt ACS worked out for this frame, before any source was asked.
     *
     * <p>This is the base for a source that only wants to <i>modify</i> the tilt — half the
     * lean, a lean about a different pivot, a lean capped at some angle. Take it, transform it,
     * return the result. There is deliberately no separate "modifier" mechanism: a modifier is
     * just a source that reads this value.</p>
     *
     * <p>It is the smoothed tilt, not the raw target, and it is <b>not</b> scaled by
     * {@link AcsClientState#tiltScale()} — the wall clamp is applied after resolution, to
     * whatever the winner returned. See {@link TiltSource} for who is responsible for what.</p>
     */
    Quaternionf acsTilt();

    /** Whether the camera is in first person right now. */
    boolean firstPerson();
}
