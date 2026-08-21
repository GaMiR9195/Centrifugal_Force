package dev.gamir.sable_cf;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sable: Centrifugal Force.
 *
 * <p>Three things, all for the local player, all on the client:</p>
 * <ul>
 *   <li><b>Rotating-frame forces.</b> The centrifugal, Euler and Coriolis acceleration of the
 *       sub-level you are tracking are added to your own velocity. Spin a drum fast enough and
 *       the outward push beats gravity, which is the whole reason a real rotor ride lets you
 *       stand on its wall.</li>
 *   <li><b>Air drag against static friction.</b> Drag is computed from your speed <i>through the
 *       air</i> - on a spinner that is mostly the deck's own tangential speed - and is then
 *       compared against what friction can hold. Under the limit you stand; over it you slide,
 *       slowly or fast; far over it you leave.</li>
 *   <li><b>A camera that follows felt gravity.</b> Not the deck plane. On a gentle list felt
 *       gravity is still almost straight down, so the camera barely moves; in a drum the
 *       centrifugal term dominates and it rolls all the way over. Driven through Aeronautics
 *       Camera Sync's {@code TiltSource} extension point.</li>
 * </ul>
 *
 * <h2>No mixins</h2>
 *
 * <p>Deliberately. Sable is reached through {@code Sable.HELPER} plus two of its duck interfaces,
 * and every one of those calls lives in {@code compat/SableAccess} so there is exactly one file to
 * look at when Sable moves. ACS is reached only through its published {@code api} package. Sure
 * Footing is not called at all - it already keeps you in the sub-level's frame through a jump arc,
 * which is the inherited-inertia half of the job, so we leave it alone and do not duplicate it.</p>
 *
 * <p>The three things that would be better as upstream API than as anything we can do from here
 * are written up in {@code docs/UPSTREAM.md}.</p>
 */
@Mod(SableCf.MOD_ID)
public final class SableCf {

    public static final String MOD_ID = "sable_cf";

    public static final Logger LOGGER = LoggerFactory.getLogger("Sable: Centrifugal Force");

    public SableCf(final ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CfConfig.SPEC);

        // Client only, and not just for the camera. Players are client-authoritative for movement:
        // the server adopts the position out of the movement packets, so writing velocity here is
        // the side that actually decides where you end up. Doing it server-side as well would have
        // the two fighting each other - this is the same split Sure Footing settled on.
        if (FMLEnvironment.dist.isClient()) {
            dev.gamir.sable_cf.client.ClientSetup.init(container);
        }
    }
}
