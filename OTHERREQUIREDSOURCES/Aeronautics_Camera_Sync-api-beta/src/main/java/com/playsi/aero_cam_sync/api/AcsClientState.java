package com.playsi.aero_cam_sync.api;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The client-only half of {@link AcsState}, reached through {@link AcsState#client()}.
 *
 * <p>Split out from {@link AcsState} because it names client-only types: a class holding a
 * {@link ClientSubLevel} field would be loaded on a dedicated server and crash the handshake.</p>
 *
 * <h2>Freshness</h2>
 *
 * <p>The vanilla camera values are captured once per frame, inside {@code Camera#setup}, right
 * before we tilt it — they are saved rather than reconstructed, because inverting the current
 * tilt quaternion does not undo what was applied (the tilt is scaled by wall proximity, and
 * that scale changes during the frame). Anyone asking before that point in the frame gets last
 * frame's values.</p>
 */
public interface AcsClientState {

    /** The camera position before ACS touched it, as captured this frame. */
    Vec3 vanillaCameraPos();

    /** The camera position after the tilt and the wall clamp. */
    Vec3 cameraPos();

    /** The camera rotation before ACS touched it, as captured this frame. */
    Quaternionf vanillaCameraRot();

    /** The camera rotation after the tilt. */
    Quaternionf cameraRot();

    /**
     * How much of the tilt survives the camera's proximity to a wall: {@code 1} in the open,
     * falling to {@code 0} with the camera flat against a wall.
     *
     * <p>Exposed because without it "the tilt is on but the correction is almost zero" looks
     * like a bug in us. It scales everything at once — rotation, position, rays, projectiles.</p>
     *
     * <p>Stays at {@code 1} in third person and whenever camera collision is off — in both cases
     * the check that produces this number is not run at all.</p>
     */
    float tiltScale();

    /**
     * Whether the camera is in first person.
     *
     * <p>In third person ACS does nothing at all unless some mod switched third person on —
     * check {@link #thirdPersonEnabled()} rather than assuming this flag alone decides.</p>
     */
    boolean firstPerson();

    /**
     * Whether the player has switched on the emergency legacy-pick fallback.
     *
     * <p>In that mode ACS does not correct aiming at all, and every {@code aim*} value in the
     * snapshot equals its {@code vanilla*} counterpart.</p>
     */
    boolean legacyPick();

    /**
     * Whether some mod has switched camera collision off through
     * {@code AcsHandle.disableCameraCollision}.
     *
     * <p>Client-only on purpose: collision is computed here and the server knows nothing about
     * it. Note this does <b>not</b> cover the player's own {@code Camera collision} setting —
     * that one is a setting, not something a mod took responsibility for.</p>
     */
    boolean cameraCollisionDisabled();

    /**
     * Mod ids currently holding the camera-collision switch. Empty if nobody is.
     *
     * <p>Order is not guaranteed. This is a diagnostic: it answers "who turned our protection
     * off" without leaving the game.</p>
     */
    List<String> cameraCollisionDisabledBy();

    /**
     * Whether some mod has switched third person on through {@code AcsHandle.enableThirdPerson}.
     *
     * <p>Off by default, in which case ACS leaves third person entirely alone.</p>
     *
     * <p>This reports who <i>asked</i>, not whether it is in force this instant: with
     * {@link #legacyPick()} on, the switch is ignored and third person stays vanilla. Same shape
     * as {@link #cameraCollisionDisabled()}, which likewise reports the request rather than the
     * effective state.</p>
     */
    boolean thirdPersonEnabled();

    /**
     * Mod ids currently holding the third-person switch. Empty if nobody is.
     *
     * <p>Order is not guaranteed. This is a diagnostic: it answers "who turned this on" without
     * leaving the game.</p>
     */
    List<String> thirdPersonEnabledBy();

    /**
     * The sub-level the tilt is being computed from, or {@code null} if there is none.
     *
     * <p>Note this is not necessarily the sub-level the player formally stands on: we keep the
     * last one for a moment after they step off, so the camera eases out instead of snapping.</p>
     */
    @Nullable ClientSubLevel tiltSubLevel();

    /**
     * The mod whose {@code TiltSource} set the tilt this frame, or {@code null} when ACS
     * computed it itself.
     *
     * <p>A diagnostic, and the one that makes {@code AcsHandle.addTiltSource} accountable: the
     * mod that claimed the frame owns everything downstream of the tilt, and this answers who
     * that was without leaving the game. Same shape as {@link #thirdPersonEnabledBy()}, but a
     * frame value rather than a registry — a source that claims nothing is not named here.</p>
     */
    @Nullable String tiltSource();
}
