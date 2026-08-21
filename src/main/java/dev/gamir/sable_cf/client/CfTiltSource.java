package dev.gamir.sable_cf.client;

import com.playsi.aero_cam_sync.api.TiltContext;
import com.playsi.aero_cam_sync.api.TiltSource;
import dev.gamir.sable_cf.CfConfig;
import dev.gamir.sable_cf.physics.CentrifugalHandler;
import dev.gamir.sable_cf.physics.CfMath;
import dev.gamir.sable_cf.physics.ForceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the camera actually points.
 *
 * <h2>Felt gravity, not the deck plane</h2>
 *
 * <p>Aligning the camera to the sub-level's plane is what makes a tilt feel like being glued to the
 * floor: the deck rolls 10 degrees, the view rolls 10 degrees, and your eyes conclude that the
 * world moved rather than that you are standing on a slope. A real person on a listing deck keeps
 * their head near vertical, because the thing their balance actually tracks is gravity.</p>
 *
 * <p>So the target here is apparent gravity - gravity plus the rotating-frame terms - and every
 * behaviour asked for falls out of that one choice, with no thresholds anywhere:</p>
 *
 * <ul>
 *   <li>Gentle list: the centrifugal term is tiny, apparent gravity is still nearly straight down,
 *       so the camera barely moves. You are standing on a slope and it looks like it.</li>
 *   <li>Inside a fast drum: the centrifugal term dwarfs gravity, apparent gravity points at the
 *       wall, and the camera rolls all the way over. The wall is the floor and it looks like it.</li>
 *   <li>A banked turn: gravity plus a lateral term gives a motorcycle lean, for free, proportional
 *       to how hard the turn actually is.</li>
 *   <li>A 360 flip: fast enough and the centrifugal term wins and the camera goes round with it;
 *       slowly and gravity wins and the world rotates around you instead. Both are correct, and
 *       which one you get is decided by the physics rather than by a config option.</li>
 * </ul>
 *
 * <h2>Two human-factors corrections</h2>
 *
 * <p>{@code deck_lean} blends a little of the deck normal back in - proprioception, the fact that
 * you can feel a slope through your feet. Small on purpose; at 1.0 it would reproduce exactly the
 * deck-locked feel it exists to avoid.</p>
 *
 * <p>{@code pitch_response} damps only the pitch part of the tilt. Roll is well tolerated; pitch is
 * what makes people put the controller down. Yaw is dropped entirely - it carries no information
 * about which way is up and is the most disorienting of the three.</p>
 *
 * <p>The result then goes through a critically damped spring, which is the honest answer to "snappy
 * and smooth at the same time": critical damping is by definition the fastest approach that never
 * overshoots.</p>
 */
public final class CfTiltSource implements TiltSource {

    private static final Vector3f WORLD_UP = new Vector3f(0.0f, 1.0f, 0.0f);

    private final TiltSpring spring = new TiltSpring();

    @Override
    public boolean appliesTo(final TiltContext context) {
        if (!CfConfig.SPEC.isLoaded() || !CfConfig.CAMERA_ENABLED.get()) {
            return false;
        }

        // Third person is ACS's business; a rolled third-person camera is disorienting in a way
        // that has nothing to do with what we are modelling.
        if (!context.firstPerson()) {
            return false;
        }

        // Keep claiming the frame while the spring still has tilt to unwind. Handing it back
        // mid-lean would snap the view level in one frame, which is the one camera artefact
        // guaranteed to be noticed.
        return CentrifugalHandler.STATE.active || this.spring.hasResidual();
    }

    @Override
    public Quaternionf tilt(final TiltContext context) {
        // Never ask ACS for its state from in here - AcsHandle#state() polls the sources, and this
        // is one of them. It is a straight infinite recursion. Everything needed is on the context.
        final ForceState state = CentrifugalHandler.STATE;
        final Vector3f rotation = new Vector3f();

        if (state.active) {
            final Vector3f up = new Vector3f(
                    (float) -state.apparent.x,
                    (float) -state.apparent.y,
                    (float) -state.apparent.z);

            if (up.lengthSquared() > 1.0e-9f && up.isFinite()) {
                up.normalize();

                final Vector3f deck = new Vector3f(
                        (float) state.normal.x, (float) state.normal.y, (float) state.normal.z);

                if (deck.lengthSquared() > 1.0e-9f) {
                    up.lerp(deck.normalize(), CfConfig.CAMERA_DECK_LEAN.get().floatValue());
                }

                if (up.lengthSquared() > 1.0e-9f) {
                    up.normalize();

                    // Shortest arc from world up to felt up. Its axis is horizontal by
                    // construction, so there is no yaw in it to have to remove.
                    rotation.set(CfMath.log(new Quaternionf().rotationTo(WORLD_UP, up)));
                    reproject(rotation);
                    CfMath.clampAngle(rotation,
                            (float) Math.toRadians(CfConfig.CAMERA_MAX_TILT_DEG.get()));
                }
            }
        }

        // deltaTicks() is ACS's realtime delta, so the spring is framerate independent - which is
        // the whole reason it hands that out instead of partialTick.
        return this.spring.step(CfMath.exp(rotation), context.deltaTicks());
    }

    /**
     * Rewrites the tilt in the player's own frame: full roll, damped pitch, no yaw.
     *
     * <p>Rotation vectors are what makes this a three-line operation - you can project and scale
     * them componentwise, which is true of neither quaternions nor Euler angles.</p>
     */
    private static void reproject(final Vector3f rotation) {
        final LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) {
            return;
        }

        final float yaw = (float) Math.toRadians(player.getYRot());

        // Minecraft yaw 0 looks along +Z.
        final Vector3f forward = new Vector3f(-(float) Math.sin(yaw), 0.0f, (float) Math.cos(yaw));
        final Vector3f right = new Vector3f(forward).cross(WORLD_UP);

        if (right.lengthSquared() < 1.0e-9f) {
            return;
        }

        right.normalize();

        // Rotation about your forward axis is roll; about your right axis is pitch. Read both
        // before overwriting.
        final float roll = rotation.dot(forward);
        final float pitch = rotation.dot(right) * CfConfig.CAMERA_PITCH_RESPONSE.get().floatValue();

        rotation.set(forward).mul(roll).add(new Vector3f(right).mul(pitch));
    }
}
