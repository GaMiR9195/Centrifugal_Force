package com.playsi.aero_cam_sync.api;

import com.playsi.aero_cam_sync.apiimpl.HandleRegistry;

/**
 * Entry point of the ACS API.
 *
 * <pre>{@code
 * private static final AcsHandle ACS = AeroCamSyncApi.forMod("mymod");
 * }</pre>
 *
 * @see AcsHandle
 */
public final class AeroCamSyncApi {

    private AeroCamSyncApi() {}

    /**
     * Returns the handle for a mod. One handle per mod id; calling this again with the same
     * id returns the same object, so it is safe to keep in a static field.
     *
     * <p>The id is what shows up in our log next to everything your mod asks us to do, so
     * pass your real mod id rather than a display name.</p>
     *
     * @param modId your mod id, non-blank
     * @throws IllegalArgumentException if {@code modId} is null or blank
     */
    public static AcsHandle forMod(String modId) {
        return HandleRegistry.forMod(modId);
    }
}
