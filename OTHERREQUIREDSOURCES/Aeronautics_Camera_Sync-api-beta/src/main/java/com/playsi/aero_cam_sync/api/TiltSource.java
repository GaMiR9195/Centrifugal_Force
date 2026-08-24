package com.playsi.aero_cam_sync.api;

import org.joml.Quaternionf;

import javax.annotation.Nullable;

/**
 * A mod's own answer to "how should the camera be tilted this frame".
 *
 * <p>Register with {@link AcsHandle#addTiltSource(int, TiltSource)}. Sources are asked in
 * descending priority order and <b>the first one whose {@link #appliesTo} returns {@code true}
 * wins</b>; the rest are not asked at all. If nobody claims the frame, ACS uses its own tilt —
 * the surface under the player — and if ACS is not tilting either, the camera is vanilla.</p>
 *
 * <h2>One tilt per frame</h2>
 *
 * <p>Resolution happens once per frame, in one place, before anything reads the tilt. That is
 * the point of the mechanism: the camera, the crosshair, every ray, the projectiles and the
 * copy sent to the server all read the same quaternion. Two mods rotating the camera in turn
 * within a frame cannot happen — one of them wins the whole frame.</p>
 *
 * <h2>What you take on by winning</h2>
 *
 * <p><b>Everything downstream of the tilt follows your quaternion, including the server.</b>
 * ACS sends the applied tilt to the server, where it decides the direction a projectile leaves
 * along, the point it leaves from, and the reach of block and entity interaction. A source is
 * therefore not a camera effect: claim a frame and you have changed where the player's
 * arrows go. Claim only the frames your scenario is actually about, and leave the rest to
 * whoever is below you.</p>
 *
 * <p>What stays with ACS: the wall clamp. Your tilt is scaled by
 * {@link AcsClientState#tiltScale()} like ours, so a camera claimed by your mod still does not
 * end up inside a block. If your mod really rotates the player and that check measures from a
 * point that no longer exists, switch it off with
 * {@link AcsHandle#disableCameraCollision(String)} — the same switch, for the same reason, as
 * without a source.</p>
 *
 * <h2>What overrides you</h2>
 *
 * <p>Two things, and they are the same two that override ACS itself:</p>
 *
 * <ul>
 *   <li>the player's mod toggle and the {@code Enabled} setting — with ACS off, no source is
 *       asked, because a player who switched the tilt off is entitled to no tilt;</li>
 *   <li>{@link AcsHandle#suppress(long)} — while any mod holds a lease, no source is asked and
 *       the tilt eases to level from whatever it was. A cutscene must be able to take the
 *       camera regardless of who is driving the tilt.</li>
 * </ul>
 *
 * <p><b>What does not cut your tilt:</b> the player's {@code Rotate camera} and {@code Shift
 * camera position} settings. Those split <i>our</i> tilt into "turn the view" and "move the eye"
 * because those are two independent halves of our own arithmetic. Your quaternion has no such
 * halves — you returned one value and you own all of it, and letting the player switch off half
 * of someone else's computation is exactly the hybrid ray this API exists to remove. Claim a
 * frame and the camera turns, the eye moves, the rays follow, and the server is told, whatever
 * those two settings say.</p>
 *
 * <p>The player is not left without a switch: the mod toggle and {@code Enabled} stop every
 * source, and so does {@link AcsHandle#suppress(long)}. Both are listed above, and both are
 * deliberately all-or-nothing rather than half-measures.</p>
 *
 * <h2>Priority</h2>
 *
 * <p>Higher number is asked first. Ties are broken by registration order. ACS's own tilt is not
 * in the list at all — it is what happens when the list runs out, so any source outranks it.</p>
 *
 * <p>There is no arbitration beyond the number: if you register at a high priority and claim
 * every frame, you have taken the tilt from everyone below you, and that is a supported thing
 * to do. It is also visible — {@link AcsClientState#tiltSource()} names the winner, and the
 * first claim is logged with your mod id, so "the tilt went strange in this modpack" is
 * answerable from a log.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>{@link #appliesTo} is called once per frame per source, up to the winner. That is cheap,
 * but it is on the frame path: decide from the {@link TiltContext} and return. No raycasts, no
 * world queries, no allocation you can avoid.</p>
 *
 * <p>Mods that register no source pay nothing: an empty list is one comparison.</p>
 *
 * <h2>Example — a mod that owns the tilt in its own dimension</h2>
 *
 * <pre>{@code
 * ACS.addTiltSource(100, new TiltSource() {
 *     @Override
 *     public boolean appliesTo(TiltContext ctx) {
 *         return ctx.player().level().dimension() == MyDimensions.ORBIT;
 *     }
 *
 *     @Override
 *     public Quaternionf tilt(TiltContext ctx) {
 *         return myGravity.orientation(ctx.partialTick());
 *     }
 * });
 * }</pre>
 *
 * <h2>Example — halving whatever ACS came up with</h2>
 *
 * <pre>{@code
 * ACS.addTiltSource(0, new TiltSource() {
 *     @Override public boolean appliesTo(TiltContext ctx) { return true; }
 *
 *     @Override public Quaternionf tilt(TiltContext ctx) {
 *         return new Quaternionf().slerp(ctx.acsTilt(), 0.5f);
 *     }
 * });
 * }</pre>
 */
public interface TiltSource {

    /**
     * Whether this source wants to set the tilt this frame.
     *
     * <p>Answer {@code false} for every frame that is not your scenario — that is what lets the
     * mods below you, and ACS itself, keep working. A source that always answers {@code true}
     * has taken the tilt for the whole session.</p>
     */
    boolean appliesTo(TiltContext context);

    /**
     * The tilt to apply, as a rotation from world-up to where up should be.
     *
     * <p>Called only when {@link #appliesTo} answered {@code true}. Returning {@code null} is
     * treated as declining after all — the next source down is asked — and is logged once, since
     * it usually means the two methods disagree about the same condition.</p>
     *
     * <p>The quaternion is copied and normalised on our side; you may return a field.</p>
     */
    @Nullable Quaternionf tilt(TiltContext context);
}
