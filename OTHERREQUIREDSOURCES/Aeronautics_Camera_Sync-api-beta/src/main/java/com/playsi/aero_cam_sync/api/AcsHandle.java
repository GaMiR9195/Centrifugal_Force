package com.playsi.aero_cam_sync.api;

import net.minecraft.world.entity.player.Player;

import java.util.function.Supplier;

/**
 * Everything one mod can ask of ACS. Obtained from {@link AeroCamSyncApi#forMod(String)}.
 *
 * <p>The handle carries your mod id, which is what makes suppression leases owned and what
 * puts your name next to your calls in our log.</p>
 */
public interface AcsHandle {

    /** The mod id this handle was created for. */
    String modId();

    // ------------------------------------------------------------------ state

    /**
     * A consistent snapshot of what ACS is doing right now.
     *
     * <p>A snapshot rather than a pile of getters on purpose: our state changes <i>within</i>
     * a frame, and a mod that asks "am I tilted?" at one point and "by how much?" at another
     * gets an inconsistent pair — a hybrid of an untilted origin and a tilted direction. That
     * is exactly the bug class this API exists to remove.</p>
     *
     * <p>Cheap, but not free — it allocates. Take one snapshot per frame and read it, rather
     * than calling this once per question.</p>
     *
     * <p>This is a pure read: it clips nothing and writes nothing.</p>
     *
     * @param player      the player to describe; on the client this is meaningful only for the
     *                    local player, other players report no tilt
     * @param partialTick render partial tick; ignored on the server
     */
    AcsState state(Player player, float partialTick);

    // ------------------------------------------------------------ suppression

    /**
     * Asks ACS to stop tilting the camera for the next {@code millis} milliseconds.
     *
     * <p>A lease, not a switch. Your mod owns its own lease: {@link #suppress(long)} takes or
     * extends yours, {@link #release()} drops yours, and the tilt stays suppressed while any
     * mod's lease is alive. You cannot drop someone else's — that is the whole point of
     * ownership, otherwise the mod whose cutscene ends first un-suppresses the one still
     * running.</p>
     *
     * <p>The camera eases back to level over the normal smoothing time instead of snapping,
     * and rays, projectiles and server sync follow it automatically. A consequence worth
     * knowing: right after {@code suppress()}, {@link AcsState#suppressed()} is already
     * {@code true} while {@code aim*} still differs from {@code vanilla*} — the camera really
     * is still tilted. Trust {@link AcsState#tiltApplied()} for "is there tilt", not
     * {@code suppressed()}.</p>
     *
     * <p>The clock is real time, but it stops while the game is paused. Leases longer than ten
     * seconds are allowed and log a warning with your mod id. All leases are dropped when the
     * player leaves the world.</p>
     *
     * <p>No-op on a dedicated server (the tilt is computed client-side), with one warning.</p>
     *
     * @param millis how long from now, in milliseconds; values &le; 0 are ignored
     */
    void suppress(long millis);

    /** Drops this mod's lease. Leases held by other mods are unaffected. */
    void release();

    /** Whether the tilt is suppressed by anyone, including other mods. */
    boolean isSuppressed();

    /** Whether this mod is currently holding a lease. */
    boolean isSuppressedByMe();

    // ------------------------------------------------------ camera collision

    /**
     * Switches off ACS's camera-collision check, and hands you the duty it was doing.
     *
     * <p><b>The contract, in one sentence: with the check off, you guarantee the camera point
     * is not inside a block.</b> ACS checks nothing after this call, and seeing through a wall
     * becomes your mod's bug, not ours.</p>
     *
     * <p>This exists for one specific kind of mod: one that <i>really</i> rotates the player in
     * the world. Our check measures from the vanilla eye — straight up in world Y from the feet
     * — and for a rotated player that point is a fiction: it ends up inside the hull while the
     * real eye is in open air. We then conclude the camera is buried in a block and scale the
     * whole tilt to zero. A mod with a hitbox rotated along with the player knows better than we
     * do, and this is how it says so.</p>
     *
     * <p>Owned like a suppression lease, and for the same reason: {@link #enableCameraCollision()}
     * drops only yours, and the collision stays off while any mod holds the switch. Unlike a
     * lease it never expires and <b>survives leaving the world</b> — it states a property of your
     * mod, not of the session, so a mod that registers once at startup keeps it. Calling it again
     * is harmless and only updates the reason.</p>
     *
     * <p>One switch covers the whole of camera collision — the same thing the player's
     * {@code Camera collision} setting covers. If you ever need only half of it, tell us.</p>
     *
     * <p>The reason is logged once with your mod id, which is what makes "why does this camera
     * clip through blocks" answerable from a log with no reproduction steps. No-op on a dedicated
     * server (collision is purely client-side), with one warning.</p>
     *
     * @param reason why your mod needs it off, in a few words; ends up in our log
     * @throws IllegalArgumentException if {@code reason} is null or blank
     */
    void disableCameraCollision(String reason);

    /** Drops this mod's switch. Switches held by other mods are unaffected. */
    void enableCameraCollision();

    /** Whether camera collision is switched off by anyone, including other mods. */
    boolean isCameraCollisionDisabled();

    /** Whether this mod is currently holding the switch. */
    boolean isCameraCollisionDisabledByMe();

    // --------------------------------------------------------- third person

    /**
     * Turns ACS on in third person, which is off by default.
     *
     * <p><b>Out of the box ACS does nothing in third person:</b> the camera is vanilla, the
     * crosshair is vanilla, and every ray leaves the player's eye exactly where vanilla put it.
     * This switch is how a mod whose scenario lives in third person asks for the tilt there.</p>
     *
     * <p>With it on, third person behaves like first person — the camera is rotated and every ray
     * follows it. That is the whole of it: there is deliberately no way to ask for one half.
     * The tilt quaternion is only computed while the tilt is being applied, so "rays follow the
     * camera, but the camera is not rotated" would aim along a value nobody is updating. Splitting
     * the switch would hand you back the hybrid ray this exists to remove.</p>
     *
     * <p>Owned like a suppression lease, and for the same reason: {@link #disableThirdPerson()}
     * drops only yours, and third person stays on while any mod holds the switch. Unlike a lease
     * it never expires and <b>survives leaving the world</b> — it states a property of your mod,
     * not of the session, so a mod that registers once at startup keeps it. Calling it again is
     * harmless and only updates the reason.</p>
     *
     * <p>The switch is read once per frame, so flipping it mid-frame takes effect on the next one
     * — never halfway through a single pick. It has no effect while the player has
     * {@code Legacy pick} turned on: that fallback path predates all of this and stays vanilla in
     * third person.</p>
     *
     * <p>The reason is logged once with your mod id. No-op on a dedicated server (the tilt in
     * third person is computed client-side), with one warning.</p>
     *
     * @param reason why your mod needs third person, in a few words; ends up in our log
     * @throws IllegalArgumentException if {@code reason} is null or blank
     */
    void enableThirdPerson(String reason);

    /** Drops this mod's switch. Switches held by other mods are unaffected. */
    void disableThirdPerson();

    /** Whether third person is switched on by anyone, including other mods. */
    boolean isThirdPersonEnabled();

    /** Whether this mod is currently holding the switch. */
    boolean isThirdPersonEnabledByMe();

    // ------------------------------------------------------------ vanilla eye

    /**
     * Runs {@code body} in a scope where the player's eye is the real, untilted one.
     *
     * <p>For render work that needs the true eye — lighting probes, entity culling, nameplate
     * placement — rather than the point the player aims from. Inside the scope our eye
     * correction is off, which also means the aiming-ray net leaves rays alone.</p>
     *
     * <p>A scope rather than an {@code enter}/{@code exit} pair so it cannot be left unbalanced;
     * nesting works. Client main thread only — from any other thread the body still runs, just
     * without the scope, and we log one warning per session.</p>
     */
    void withVanillaEye(Runnable body);

    /** {@link #withVanillaEye(Runnable)} for a body that returns a value. */
    <T> T withVanillaEye(Supplier<T> body);

    // ------------------------------------------------------- extension points

    /**
     * Registers a listener for tilt start/stop and suppression changes.
     *
     * <p>Register once during setup. Listeners are called on the thread the event happens on
     * — the client main thread for tilt events.</p>
     */
    void addListener(TiltListener listener);

    /**
     * Registers a policy that decides whether a given ray should be treated as an aiming ray.
     *
     * <p>Register once during setup, and read {@link AimPolicy} first — {@code decide} is
     * called from inside {@code clip}, dozens of times a frame.</p>
     */
    void addPolicy(AimPolicy policy);

    /**
     * Registers a source that decides the tilt itself, taking it away from ACS on the frames
     * it claims.
     *
     * <p>Register once during setup, and read {@link TiltSource} first — winning a frame moves
     * the player's aim, projectiles and interaction reach, on the server as well as here.</p>
     *
     * <p>Sources are asked in descending priority order, ties broken by registration order, and
     * the first one to claim the frame wins it. ACS's own tilt is what happens when nobody
     * claims, so any priority outranks it. Registering the same source twice registers it
     * twice — unlike the ownership switches, there is nothing to be idempotent about.</p>
     *
     * <p>No-op on a dedicated server (the tilt is computed client-side), with one warning.</p>
     *
     * @param priority higher is asked first; use {@code 0} unless you have a reason
     */
    void addTiltSource(int priority, TiltSource source);
}
