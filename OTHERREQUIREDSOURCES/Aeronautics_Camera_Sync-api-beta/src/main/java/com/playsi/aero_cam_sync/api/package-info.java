/**
 * Public API of <b>Aeronautics Camera Sync</b> (ACS).
 *
 * <h2>What ACS does</h2>
 *
 * <p>ACS tilts the player's camera to match the surface of the Sable sub-level (contraption)
 * they are standing on. Because the camera moves and rotates, the point the player is
 * <i>actually</i> looking from stops being {@code player.getEyePosition()} — it is that point
 * rotated around the player's feet. Any ray a mod builds from the raw eye position will be
 * parallel-shifted away from the crosshair, by up to a block at a noticeable roll.</p>
 *
 * <p>Most mods need to do nothing: ACS catches aiming rays inside {@code BlockGetter#clip} and
 * shifts them for you. This API exists for the cases it cannot catch, for mods that want to
 * know what ACS is doing, and for mods that need ACS to stand back for a while.</p>
 *
 * <h2>Entry point</h2>
 *
 * <pre>{@code
 * private static final AcsHandle ACS = AeroCamSyncApi.forMod("mymod");
 * }</pre>
 *
 * <p>One handle per mod; it is safe to keep in a static field. Everything else hangs off it —
 * see {@link com.playsi.aero_cam_sync.api.AcsHandle}.</p>
 *
 * <h2>ACS may not be installed</h2>
 *
 * <p>There is deliberately no {@code isPresent()} check in this API: if you reached one of our
 * classes, we are loaded. Guard the other way around — check before the first call, and keep
 * the call in a separate class so the classloader does not touch our types too early:</p>
 *
 * <pre>{@code
 * if (ModList.get().isLoaded("aero_cam_sync")) AcsBridge.init();
 * }</pre>
 *
 * <h2>Boundary and compatibility promise</h2>
 *
 * <p>This package — and <b>only</b> this package — is public. Everything else in
 * {@code com.playsi.aero_cam_sync} is internal: {@code ClipNet}, {@code PickScope},
 * {@code RenderEyeScope}, {@code CameraController}, {@code TiltAccess}, every mixin. Those
 * change without notice, including in patch releases.</p>
 *
 * <p>Within {@code 1.x} the signatures in this package do not break. Anything removed is
 * {@code @Deprecated} for at least one minor release first.</p>
 *
 * <h2>Sides and threads</h2>
 *
 * <p>The tilt itself is computed on the client. {@link com.playsi.aero_cam_sync.api.AcsState}
 * works on both sides; the client-only half is reached through
 * {@link com.playsi.aero_cam_sync.api.AcsState#client()}, which returns {@code null} on a
 * dedicated server.</p>
 *
 * <p>{@link com.playsi.aero_cam_sync.api.AcsHandle#withVanillaEye(Runnable)} and the aiming
 * pipeline are client main-thread only. Background rays are intentionally left alone (a sound
 * mod tracing from a thread pool once deadlocked the client), so calls from other threads are
 * no-ops with a single warning.</p>
 */
package com.playsi.aero_cam_sync.api;
