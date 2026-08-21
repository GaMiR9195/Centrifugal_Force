package dev.gamir.sable_cf.client;

import com.playsi.aero_cam_sync.api.AcsHandle;
import com.playsi.aero_cam_sync.api.AeroCamSyncApi;
import dev.gamir.sable_cf.SableCf;

/**
 * The only file that touches Aeronautics Camera Sync, and only ever through its published
 * {@code api} package.
 *
 * <p>Using ACS rather than writing our own camera is not laziness - a tilted first-person camera
 * has to drag block outline selection, item use rays, projectile spawn direction, the third-person
 * camera collision probe and the server's opinion of where you are looking along with it. ACS has
 * already done all of that, and two mods both rotating the camera would simply fight. So we hand it
 * a {@code TiltSource} and let it own the frame.</p>
 *
 * <p>We do <b>not</b> call {@code disableCameraCollision()}: that switch exists for mods that
 * rotate the player entity itself, and we do not. Leaving it on is what keeps the third-person
 * camera out of walls.</p>
 */
public final class AcsBridge {

    /**
     * Priority for our tilt source.
     *
     * <p>ACS polls sources highest-first and the first one to answer {@code appliesTo == true}
     * takes the whole frame. Above ACS's own tilt, because when the player is on a rotating
     * sub-level our answer is the one that should win; on every other frame we decline and ACS's
     * own behaviour is what runs.</p>
     */
    private static final int PRIORITY = 100;

    private static AcsHandle handle;
    private static boolean tiltSourceActive;

    public static void init() {
        try {
            handle = AeroCamSyncApi.forMod(SableCf.MOD_ID);
        } catch (final Throwable throwable) {
            SableCf.LOGGER.warn("Could not get an ACS handle - camera tilt is off.", throwable);
            return;
        }

        try {
            handle.addTiltSource(PRIORITY, new CfTiltSource());
            tiltSourceActive = true;
            SableCf.LOGGER.info("Registered our tilt source with Aeronautics Camera Sync at priority {}.", PRIORITY);
        } catch (final LinkageError error) {
            // addTiltSource() only exists on the api-beta line of ACS, which is newer than the
            // published 1.3.7 (that one has addListener / addPolicy and no way to supply a tilt).
            // Both NoSuchMethodError and NoClassDefFoundError land here, so an older ACS costs us
            // the camera and nothing else.
            SableCf.LOGGER.warn(
                    "This build of Aeronautics Camera Sync has no addTiltSource() - camera tilt is off. "
                            + "It needs an api-beta build (newer than 1.3.7); the physics and the overlay "
                            + "are unaffected.");
        }
    }

    /** True when ACS accepted our tilt source, i.e. the camera feature is actually live. */
    public static boolean tiltSourceActive() {
        return tiltSourceActive;
    }

    private AcsBridge() {
    }
}
