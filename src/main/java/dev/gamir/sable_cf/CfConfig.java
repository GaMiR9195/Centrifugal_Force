package dev.gamir.sable_cf;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config, and the only place a number lives. Commands write straight into these values and save,
 * so nothing needs a restart and nothing needs a second copy of the defaults.
 *
 * <h2>Why every force is one flat number</h2>
 *
 * <p>Each subsystem has exactly one strength knob, on the subsystem itself: {@code strength} is
 * what {@code /sable_cf air_resistance 1.4} writes. There are no reference speeds, exponents or
 * shaping constants to discover, because a knob you have to combine with another knob in your head
 * is not a knob you can tune. The shaping constants still exist - they are just fixed at values
 * that were chosen once, below, where they can be read.</p>
 *
 * <h2>Units</h2>
 *
 * <p>Sable reports velocities in m/s (blocks per second), and Minecraft's gravity of
 * 0.08 blocks/tick^2 is 32 m/s^2 in those units - that is {@link #GRAVITY}. "1 g" anywhere in this
 * mod means 32, not 9.81.</p>
 *
 * <h2>Why this is a COMMON config and not a CLIENT one</h2>
 *
 * <p>The rotated hitbox has to exist on the server too, or the server keeps testing an upright box
 * against the same blocks and shoves you back out of them. So the hitbox code is common, so the
 * config that gates it has to be common as well.</p>
 */
public final class CfConfig {

    /**
     * Minecraft's gravity in Sable's units: 0.08 blocks/tick^2 * 20^2 = 32 m/s^2.
     *
     * <p>Not 9.81. Mixing the two is the easiest way to get every threshold in here wrong by a
     * factor of three.</p>
     */
    public static final double GRAVITY = 32.0;

    // ---------------------------------------------------------------- fixed shaping constants

    /**
     * Air speed, m/s, at which drag reaches one gravity at {@code strength = 1}.
     *
     * <p>Raised from the physically-flavoured 30 to 40: at 30 a mid-sized ride was already trying
     * to peel you off before it was going fast enough to hold you on, which is the wrong order for
     * the ride to be fun.</p>
     */
    private static final double AIR_REFERENCE_SPEED = 40.0;

    /**
     * Exponent of the drag law. Real aerodynamic drag is quadratic; this is 1.35.
     *
     * <p>Quadratic drag has almost no effect until a knee and then removes all control at once,
     * which reads as "nothing, nothing, thrown off". At 1.35 the ramp is gradual enough that
     * sliding is a state you can be in and steer against for a few seconds instead of a transition
     * you pass through. Still superlinear, so speed still genuinely matters.</p>
     */
    private static final double AIR_EXPONENT = 1.35;

    /**
     * Soft ceiling on drag, in g. Past this the curve compresses instead of continuing.
     *
     * <p>Without it a fast enough ride produces an arbitrarily large sideways acceleration and
     * being swept off stops looking like being swept off and starts looking like a teleport.</p>
     */
    private static final double AIR_SOFT_CAP_G = 2.2;

    /** Sharpness of the surface-normal blend. Lower is rounder, higher approaches a hard switch. */
    public static final double SURFACE_BLEND_SHARPNESS = 7.0;

    /** Half-life, seconds, of the body orientation chasing its target. */
    public static final double BODY_HALF_LIFE = 0.13;

    /** Half-life, seconds, of the low-pass that defines "where has down been lately". */
    public static final double LOOP_AVERAGE_HALF_LIFE = 0.45;

    public static final ModConfigSpec SPEC;

    // ---------------------------------------------------------------- rotating frame

    public static final ModConfigSpec.BooleanValue CENTRIFUGAL_ENABLED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue CORIOLIS_STRENGTH;
    public static final ModConfigSpec.DoubleValue MAX_ACCEL_G;

    // ---------------------------------------------------------------- air resistance

    public static final ModConfigSpec.BooleanValue AIR_ENABLED;
    public static final ModConfigSpec.DoubleValue AIR_STRENGTH;

    // ---------------------------------------------------------------- grip

    public static final ModConfigSpec.BooleanValue GRIP_ENABLED;
    public static final ModConfigSpec.DoubleValue GRIP_STRENGTH;
    public static final ModConfigSpec.DoubleValue GRIP_BRACE_BONUS;
    public static final ModConfigSpec.DoubleValue GRIP_MIN_PRESS_G;
    public static final ModConfigSpec.DoubleValue GRIP_FULL_PRESS_G;

    // ---------------------------------------------------------------- hitbox

    public static final ModConfigSpec.BooleanValue HITBOX_ENABLED;
    public static final ModConfigSpec.DoubleValue HITBOX_AMOUNT;

    // ---------------------------------------------------------------- camera

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ModConfigSpec.DoubleValue CAMERA_AMOUNT;
    public static final ModConfigSpec.DoubleValue CAMERA_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DAMPING;
    public static final ModConfigSpec.DoubleValue CAMERA_PITCH_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DECK_LEAN;
    public static final ModConfigSpec.DoubleValue CAMERA_MAX_TILT_DEG;
    public static final ModConfigSpec.DoubleValue CAMERA_SLEW_DEG_PER_S;
    public static final ModConfigSpec.DoubleValue CAMERA_JOLT_GAIN;
    public static final ModConfigSpec.DoubleValue CAMERA_LOOP_SUPPRESSION;
    public static final ModConfigSpec.DoubleValue CAMERA_WALK_DAMPING;

    // ---------------------------------------------------------------- debug

    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;
    public static final ModConfigSpec.BooleanValue DEBUG_TEXT;
    public static final ModConfigSpec.DoubleValue DEBUG_ACCEL_SCALE;
    public static final ModConfigSpec.DoubleValue DEBUG_VELOCITY_SCALE;
    public static final ModConfigSpec.DoubleValue DEBUG_MIN_LENGTH;
    public static final ModConfigSpec.DoubleValue DEBUG_MAX_LENGTH;
    public static final ModConfigSpec.DoubleValue DEBUG_ALPHA;
    public static final ModConfigSpec.IntValue DEBUG_SMOOTHING_MS;

    static {
        final ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Fictitious forces of the sub-level you are standing on.",
                        "Centrifugal is the one that lets a fast spinner hold you against its wall:",
                        "|a| = omega^2 * r, so at 5 blocks radius you need about 2.5 rad/s (~24 rpm)",
                        "to match gravity, which is roughly what a real rotor ride actually spins at.")
                .push("centrifugal_force");

        CENTRIFUGAL_ENABLED = b
                .comment("/sable_cf centrifugal_force enable|disable")
                .define("enabled", true);

        CENTRIFUGAL_STRENGTH = b
                .comment("The one knob. 1.0 is the physical value. /sable_cf centrifugal_force <value>",
                        "Below 1 makes rides gentler than reality, above 1 makes a slow spinner usable.")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        CORIOLIS_STRENGTH = b
                .comment("Scale on the Coriolis term (-2 omega x v), the one driven by your own walking.",
                        "Full strength is physically right but reads as a mysterious sideways pull, and",
                        "Sure Footing already rotates your velocity with the frame, which cancels most of",
                        "what it would have done. 0.35 keeps the flavour without the fight.")
                .defineInRange("coriolis_strength", 0.35, 0.0, 2.0);

        MAX_ACCEL_G = b
                .comment("Hard clamp on everything this mod adds, in g (1 g = 32 m/s^2).",
                        "A safety rail, not a tuning knob: a contraption that teleports produces one tick",
                        "of enormous bogus acceleration, and without this you get launched into orbit.")
                .defineInRange("max_accel_g", 8.0, 0.5, 64.0);

        b.pop();

        b.comment("Air drag on the player. On a spinning contraption your speed through the air is",
                        "mostly the deck's own tangential speed, which is why standing still on a fast",
                        "spinner still tries to peel you off.",
                        "Deliberately gentler than reality: superlinear rather than quadratic, and soft",
                        "capped, so sliding is a state you can steer in rather than a cliff you fall off.")
                .push("air_resistance");

        AIR_ENABLED = b
                .comment("/sable_cf air_resistance enable|disable")
                .define("enabled", true);

        AIR_STRENGTH = b
                .comment("The one knob. /sable_cf air_resistance <value>",
                        "1.0 is the tuned default. Higher = flimsier player, swept off sooner.",
                        "0 is the same as disabling it.")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        b.pop();

        b.comment("How much tangential force your feet can hold before you start sliding, and how",
                        "hard you have to be pressed into a surface before it counts as footing at all.",
                        "This is the whole stand / slide / get-swept-off decision.")
                .push("grip");

        GRIP_ENABLED = b
                .comment("/sable_cf grip enable|disable. Off means no friction at all: everything slides.")
                .define("enabled", true);

        GRIP_STRENGTH = b
                .comment("The one knob: static friction coefficient. /sable_cf grip <value>",
                        "0.85 is about right for boots on a metal deck. Raise it and you can walk around",
                        "a drum that should be flinging you off; lower it and a mild bank is an ice rink.")
                .defineInRange("strength", 0.85, 0.0, 4.0);

        GRIP_BRACE_BONUS = b
                .comment("Friction multiplier while sneaking - the 'hold on' input, so resisting is a",
                        "choice you make rather than something that either always or never works.")
                .defineInRange("brace_bonus", 1.9, 1.0, 4.0);

        GRIP_MIN_PRESS_G = b
                .comment("Press, in g, below which a surface is not holding you at all and the body stays",
                        "upright. This is the bottom of the partial-tilt ramp.")
                .defineInRange("min_press_g", 0.2, 0.0, 4.0);

        GRIP_FULL_PRESS_G = b
                .comment("Press, in g, at which the body is fully aligned with the surface - standing on",
                        "a wall as if it were the floor. This is the top of the partial-tilt ramp.",
                        "1.2 on purpose rather than something dramatic: a washing-machine ride is not",
                        "going to be spinning at 4 g, and the effect has to be reachable to be a feature.")
                .defineInRange("full_press_g", 1.2, 0.1, 16.0);

        b.pop();

        b.comment("Rotating the player's actual collision box with the body, not just the model.",
                        "An AABB cannot rotate, so what this does is refit the box around the rotated",
                        "body every time it is rebuilt. Consequences worth knowing about are in the README.")
                .push("hitbox");

        HITBOX_ENABLED = b
                .comment("/sable_cf hitbox enable|disable")
                .define("enabled", true);

        HITBOX_AMOUNT = b
                .comment("How much of the body tilt the collision box is allowed to follow, 0..1.",
                        "/sable_cf hitbox <value>. 1.0 means the box tracks the body exactly.",
                        "Lower it if a particular contraption keeps wedging you in doorways; the visual",
                        "and camera tilt are unaffected.")
                .defineInRange("amount", 1.0, 0.0, 1.0);

        b.pop();

        b.comment("Camera. Requires Aeronautics Camera Sync with addTiltSource() (api-beta, > 1.3.7).",
                        "The target is FELT gravity, not the deck plane - that single choice is the",
                        "difference between 'barely moves on a gentle list' and 'rolls all the way over",
                        "inside a drum', with no threshold anywhere.")
                .push("camera");

        CAMERA_ENABLED = b
                .comment("/sable_cf camera enable|disable")
                .define("enabled", true);

        CAMERA_AMOUNT = b
                .comment("Overall tilt amount. The one knob. /sable_cf camera <value>",
                        "0 keeps the camera level while everything else still works.")
                .defineInRange("amount", 1.0, 0.0, 2.0);

        CAMERA_RESPONSE = b
                .comment("Base spring frequency in rad/s - how eagerly the camera chases its target.",
                        "9 settles in about 0.2 s. Sharp manoeuvres raise this on their own, see",
                        "jolt_gain; this is the resting value, not the peak.")
                .defineInRange("response", 9.0, 1.0, 40.0);

        CAMERA_DAMPING = b
                .comment("Damping ratio. 1.0 is critical damping: fastest possible approach with zero",
                        "overshoot, which is the mathematical definition of crisp without wobble.")
                .defineInRange("damping", 1.0, 0.4, 2.0);

        CAMERA_JOLT_GAIN = b
                .comment("How much a sharp manoeuvre speeds the camera up, driven by the sub-level's",
                        "ANGULAR ACCELERATION rather than by how big the centrifugal vector happens to be.",
                        "That is the fix for 'sometimes huge, sometimes invisible': the old behaviour keyed",
                        "off a quantity that also grows with radius and steady spin, so an identical flick",
                        "of the controls felt different depending on where you were standing. Angular",
                        "acceleration is the thing that actually corresponds to a sharp input.",
                        "The curve is x/(1+x) - saturating and monotonic, so it cannot spike.")
                .defineInRange("jolt_gain", 1.6, 0.0, 6.0);

        CAMERA_LOOP_SUPPRESSION = b
                .comment("How much to stop following felt-down once the sub-level is going all the way",
                        "round, 0..1. During a loop, felt-down sweeps a full circle, and a camera that",
                        "tracks it honestly rolls 360 degrees - which is the single most nauseating thing",
                        "a camera can do and is also not what a human head does. A real person in a loop",
                        "keeps their head with their body and lets the world go round them.",
                        "So above a sustained rotation the target fades to the recent AVERAGE of felt-down,",
                        "which over a full revolution is stable. 0 restores the old spinning behaviour.")
                .defineInRange("loop_suppression", 0.85, 0.0, 1.0);

        CAMERA_WALK_DAMPING = b
                .comment("How much to calm the camera down while you are walking under your own power,",
                        "0..1. Walking on a spinner changes your radius and adds a Coriolis term every",
                        "tick, so the felt-down target jitters even though the ride is doing nothing new.",
                        "That is a different signal from a bank and has to be damped separately, or",
                        "ordinary walking is nauseating on its own. Holding on to a bank is untouched -",
                        "this only damps the part of the target that your own movement created.")
                .defineInRange("walk_damping", 0.65, 0.0, 1.0);

        CAMERA_PITCH_RESPONSE = b
                .comment("How much of the pitch component of the tilt to apply, 0..1. Roll is well",
                        "tolerated by the human vestibular system; pitch is what makes people queasy.",
                        "Yaw is dropped entirely.")
                .defineInRange("pitch_response", 0.5, 0.0, 1.0);

        CAMERA_DECK_LEAN = b
                .comment("Small blend from felt-up towards the surface normal, 0..1 - the proprioceptive",
                        "hint that tells you which way the floor is. Keep it small: at 1.0 it would",
                        "reproduce exactly the deck-locked 'glued to the plane' feel it exists to avoid.")
                .defineInRange("deck_lean", 0.2, 0.0, 1.0);

        CAMERA_MAX_TILT_DEG = b
                .comment("Hard cap on total tilt in degrees. 65 lets a drum roll you a long way while",
                        "stopping fully inverted views, which read as broken rather than as intense.")
                .defineInRange("max_tilt_deg", 65.0, 0.0, 90.0);

        CAMERA_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the camera may turn, deg/s. The spring handles ordinary motion;",
                        "this exists for the pathological case - a contraption that snaps 180 degrees in",
                        "one tick - so the camera leans over instead of whipping.")
                .defineInRange("slew_deg_per_s", 240.0, 30.0, 1440.0);

        b.pop();

        b.comment("Debug overlay: flat camera-facing arrows with 2D triangular heads.")
                .push("debug");

        DEBUG_OVERLAY = b
                .comment("/sable_cf debug_overlay")
                .define("overlay", false);

        DEBUG_TEXT = b
                .comment("Also print the numbers behind the arrows in the top-left corner.")
                .define("text", true);

        DEBUG_ACCEL_SCALE = b
                .comment("Blocks drawn per m/s^2. 0.025 makes 1 g exactly 0.8 blocks long, so an arrow",
                        "about as long as you are tall is about one gravity.")
                .defineInRange("accel_scale", 0.025, 0.001, 1.0);

        DEBUG_VELOCITY_SCALE = b
                .comment("Blocks drawn per m/s.")
                .defineInRange("velocity_scale", 0.08, 0.001, 2.0);

        DEBUG_MIN_LENGTH = b
                .comment("Arrows shorter than this are drawn at this length instead, so a small but real",
                        "force is still readable as a direction. Below the dead zone nothing is drawn at",
                        "all - see the README for the three-band behaviour.")
                .defineInRange("min_length", 0.28, 0.0, 4.0);

        DEBUG_MAX_LENGTH = b
                .comment("Arrows are never drawn longer than this, so a 6 g spike does not fill the",
                        "screen with one arrow.")
                .defineInRange("max_length", 3.5, 0.2, 32.0);

        DEBUG_ALPHA = b
                .comment("Arrow opacity, 0..1. Translucent on purpose: the arrows sit in front of your",
                        "own model and you still need to see what you are standing on.")
                .defineInRange("alpha", 0.7, 0.05, 1.0);

        DEBUG_SMOOTHING_MS = b
                .comment("Half-life of the arrow smoothing in milliseconds. Small on purpose: the arrows",
                        "must stop flickering without lying about when a force actually arrived.")
                .defineInRange("smoothing_ms", 70, 0, 1000);

        b.pop();

        SPEC = b.build();
    }

    /**
     * Drag acceleration magnitude, m/s^2, for an air speed in m/s.
     *
     * <p>Superlinear but not quadratic, and soft capped. Kept here rather than in the handler so
     * that the shaping constants and the knob that scales them are the same page of code.</p>
     */
    public static double dragMagnitude(final double airSpeed) {
        if (!(airSpeed > 0.0) || !Double.isFinite(airSpeed)) {
            return 0.0;
        }

        final double strength = AIR_STRENGTH.get();

        if (strength <= 0.0) {
            return 0.0;
        }

        final double normalised = airSpeed / AIR_REFERENCE_SPEED;
        final double raw = strength * Math.pow(normalised, AIR_EXPONENT);

        // x / (1 + x/cap) -> approaches cap instead of crossing it, with no corner at the knee.
        final double cap = AIR_SOFT_CAP_G;
        final double limited = raw / (1.0 + raw / cap);

        return limited * GRAVITY;
    }

    /**
     * The partial-tilt ramp: 0 while barely pressed, 1 once firmly pressed, smooth in between.
     *
     * @param press normal load in m/s^2
     */
    public static double tiltFromPress(final double press) {
        final double low = GRIP_MIN_PRESS_G.get() * GRAVITY;
        final double high = GRIP_FULL_PRESS_G.get() * GRAVITY;

        if (!(high > low)) {
            return press >= high ? 1.0 : 0.0;
        }

        final double t = Math.min(1.0, Math.max(0.0, (press - low) / (high - low)));

        // Smoothstep: zero slope at both ends, so neither losing nor gaining footing snaps.
        return t * t * (3.0 - 2.0 * t);
    }

    private CfConfig() {
    }
}
