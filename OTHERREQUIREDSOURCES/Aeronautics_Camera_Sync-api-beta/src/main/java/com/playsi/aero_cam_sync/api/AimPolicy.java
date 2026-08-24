package com.playsi.aero_cam_sync.api;

/**
 * Decides whether a ray passing through {@code BlockGetter#clip} is an aiming ray.
 *
 * <p>ACS already catches aiming rays on its own: it shifts any ray that starts exactly at the
 * owner's vanilla eye position. That filter is deliberately strict — it is what makes double
 * shifting impossible by construction and what keeps other mods' physics out. A policy is for
 * the two cases the filter gets wrong:</p>
 *
 * <ul>
 *   <li>{@link Decision#SHIFT} — "this <i>is</i> my aiming ray, even though it does not start
 *       at the eye". Mods that build the origin their own way;</li>
 *   <li>{@link Decision#KEEP_VANILLA} — "this is suspension/wheel/collision maths, leave it
 *       alone", for the rare case where such a ray happens to start exactly at the eye;</li>
 *   <li>{@link Decision#PASS} — not my business, let the normal rule decide. This is the
 *       answer for almost every ray you will see.</li>
 * </ul>
 *
 * <h2>Resolution order</h2>
 *
 * <p>Cheap facts are computed first, then policies are asked in registration order, and the
 * first non-{@code PASS} answer wins. If two policies disagree on the same ray we log both once
 * per session and take the first. If everyone passes, the normal rule applies.</p>
 *
 * <h2>Cost — read this before registering one</h2>
 *
 * <p>{@code decide} is called from inside {@code clip}, dozens of times per frame. <b>No
 * allocations and no raycasts inside it.</b> Decide from the fields of the
 * {@link AimQuery} and return.</p>
 *
 * <p>Mods that register no policy pay nothing: an empty policy list is one comparison.</p>
 */
public interface AimPolicy {

    enum Decision {
        /** Treat this ray as an aiming ray and shift its origin into the tilted camera. */
        SHIFT,
        /** Leave this ray exactly as it is, even if the normal rule would have shifted it. */
        KEEP_VANILLA,
        /** No opinion — let the next policy, then the normal rule, decide. */
        PASS
    }

    Decision decide(AimQuery query);
}
