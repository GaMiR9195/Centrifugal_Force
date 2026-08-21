package dev.gamir.sable_cf;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config, and the only place a number lives. The commands write straight into these values
 * and save, so nothing needs a restart and nothing needs a second copy of the defaults.
 *
 * <h2>Why these units</h2>
 *
 * <p>Every knob is a physical quantity rather than a multiplier, because a multiplier on a formula
 * you cannot see is impossible to tune. Sable reports velocities in m/s (blocks per second), and
 * Minecraft's gravity of 0.08 blocks/tick^2 is 32 m/s^2 in those units - that is {@link #GRAVITY},
 * and it is the reference the knobs below are expressed against. "1 g" anywhere in this mod means
 * 32 m/s^2, not 9.81.</p>
 */
public final class CfConfig {

    /**
     * Minecraft's gravity in Sable's units: 0.08 blocks/tick^2 * 20^2 = 32 m/s^2.
     *
     * <p>Not 9.81. Mixing the two is the single easiest way to get every threshold in here wrong
     * by a factor of three.</p>
     */
    public static final double GRAVITY = 32.0;

    public static final ModConfigSpec SPEC;

    // ------------------------------------------------------------------ rotating frame

    public static final ModConfigSpec.BooleanValue CENTRIFUGAL_ENABLED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue EULER_STRENGTH;
    public static final ModConfigSpec.DoubleValue CORIOLIS_STRENGTH;
    public static final ModConfigSpec.DoubleValue MAX_ACCEL_G;

    // ------------------------------------------------------------------ air resistance

    public static final ModConfigSpec.BooleanValue AIR_ENABLED;
    public static final ModConfigSpec.DoubleValue AIR_REFERENCE_SPEED;

    // ------------------------------------------------------------------ grip / release

    public static final ModConfigSpec.DoubleValue GRIP_FRICTION;
    public static final ModConfigSpec.DoubleValue GRIP_BRACE_BONUS;
    public static final ModConfigSpec.DoubleValue GRIP_MIN_PRESS_G;
    public static final ModConfigSpec.BooleanValue RELEASE_TRACKING;
    public static final ModConfigSpec.DoubleValue RELEASE_SPEED;

    // ------------------------------------------------------------------ camera

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ModConfigSpec.DoubleValue CAMERA_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DAMPING;
    public static final ModConfigSpec.DoubleValue CAMERA_PITCH_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DECK_LEAN;
    public static final ModConfigSpec.DoubleValue CAMERA_MAX_TILT_DEG;
    public static final ModConfigSpec.DoubleValue CAMERA_SLEW_DEG_PER_S;

    // ------------------------------------------------------------------ debug

    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;
    public static final ModConfigSpec.BooleanValue DEBUG_TEXT;
    public static final ModConfigSpec.DoubleValue DEBUG_ACCEL_SCALE;
    public static final ModConfigSpec.DoubleValue DEBUG_VELOCITY_SCALE;
    public static final ModConfigSpec.IntValue DEBUG_SMOOTHING_MS;

    static {
        final ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Fictitious forces of the sub-level you are tracking.",
                        "Centrifugal is the one that lets a fast spinner hold you against its wall:",
                        "|a| = omega^2 * r, so at 5 blocks radius you need about 2.5 rad/s (~24 rpm)",
                        "to match gravity, which is roughly what a real rotor ride actually spins at.")
                .push("centrifugal_force");

        CENTRIFUGAL_ENABLED = b
                .comment("Master switch for all three rotating-frame terms. /sable_cf centrifugal_force enable|disable")
                .define("enabled", true);

        CENTRIFUGAL_STRENGTH = b
                .comment("Scale on the centrifugal term. 1.0 is the physical value.",
                        "Below 1 makes rides gentler than reality, above 1 makes a slow spinner usable.")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        EULER_STRENGTH = b
                .comment("Scale on the Euler term (-alpha x r): the shove you feel when the spin rate itself changes.",
                        "This is what throws you when a contraption starts or brakes. Keep at 1.0 unless a",
                        "jerky animated contraption is punching you; alpha is a second difference and is noisy.")
                .defineInRange("euler_strength", 1.0, 0.0, 2.0);

        CORIOLIS_STRENGTH = b
                .comment("Scale on the Coriolis term (-2 omega x v). Full strength is physically right but",
                        "reads as a mysterious sideways pull while walking, and Sure Footing already rotates",
                        "your velocity with the frame, which cancels most of what Coriolis would have done.",
                        "0.35 keeps the flavour without the fight. Set 0 if you run without Sure Footing and",
                        "want dead-straight walking.")
                .defineInRange("coriolis_strength", 0.35, 0.0, 2.0);

        MAX_ACCEL_G = b
                .comment("Hard clamp on everything this mod adds, in g (1 g = 32 m/s^2).",
                        "A safety rail, not a tuning knob: a contraption that teleports gives a huge bogus",
                        "omega for one tick, and without this you get launched into orbit.")
                .defineInRange("max_accel_g", 8.0, 0.5, 64.0);

        b.pop();

        b.comment("Air drag on the player. On a spinning contraption your speed through the air is",
                        "mostly the deck's own tangential speed, which is why standing still on a fast",
                        "spinner still tries to peel you off.")
                .push("air_resistance");

        AIR_ENABLED = b
                .comment("/sable_cf air_resistance enable|disable")
                .define("enabled", true);

        AIR_REFERENCE_SPEED = b
                .comment("The one number that sets how hard drag is, in m/s. Deliberately not a multiplier.",
                        "It is the air speed at which drag equals gravity: a = g * (v / reference_speed)^2.",
                        "So 30 means 'a 30 m/s blast pushes you sideways as hard as gravity pulls you down',",
                        "and because the law is quadratic, halving this makes drag four times stronger.",
                        "Lower = flimsier player, swept off sooner. Higher = you can stand in a gale.")
                .defineInRange("reference_speed", 30.0, 2.0, 200.0);

        b.pop();

        b.comment("How much tangential force your feet can hold before you start sliding.",
                        "This is the whole stand / slide / get-swept-off decision: friction can hold up to",
                        "friction * normal_load, and only the excess actually moves you.")
                .push("grip");

        GRIP_FRICTION = b
                .comment("Static friction coefficient. 0.85 is about right for boots on a metal deck.",
                        "Raise it and you can walk around a drum that should be flinging you off;",
                        "lower it and a mild bank turns into an ice rink.")
                .defineInRange("friction", 0.85, 0.0, 4.0);

        GRIP_BRACE_BONUS = b
                .comment("Friction multiplier while sneaking - the 'hold on' input, so resisting is a choice",
                        "you make rather than something that either always or never works.")
                .defineInRange("brace_bonus", 1.9, 1.0, 4.0);

        GRIP_MIN_PRESS_G = b
                .comment("How hard you must be pressed into a surface, in g, before it counts as footing at all.",
                        "Below this the surface is not holding you and every force applies in full.")
                .defineInRange("min_press_g", 0.25, 0.0, 4.0);

        RELEASE_SPEED = b
                .comment("Tangential speed of the deck under you, in m/s, above which losing grip counts as",
                        "being flung rather than merely slipping. Only used together with release_tracking.")
                .defineInRange("release_speed", 14.0, 0.0, 200.0);

        RELEASE_TRACKING = b
                .comment("EXPERIMENTAL, off by default. When you are flung above release_speed, drop Sable's",
                        "tracking sub-level so Sable's own inherited-velocity handover happens immediately",
                        "instead of Sure Footing re-attaching you for up to exit_distance_blocks.",
                        "Off by default because it is a write into Sable state that Sure Footing also writes,",
                        "in the same tick phase, and who wins depends on handler registration order.",
                        "The clean fix is an API on Sure Footing's side - see docs/UPSTREAM.md.")
                .define("release_tracking", false);

        b.pop();

        b.comment("Camera. Requires Aeronautics Camera Sync with addTiltSource() (api-beta, > 1.3.7).",
                        "The target is FELT gravity, not the deck plane. That single choice is the difference",
                        "between 'barely moves on a gentle list' and 'rolls all the way over inside a drum',",
                        "with no threshold anywhere: on a level deck felt gravity IS straight down.")
                .push("camera");

        CAMERA_ENABLED = b
                .comment("/sable_cf camera enable|disable")
                .define("enabled", true);

        CAMERA_RESPONSE = b
                .comment("Spring natural frequency in rad/s - how eagerly the camera chases the target.",
                        "11 settles in about 0.15 s. This is the 'snappy' half of snappy-and-smooth;",
                        "the damping below is what keeps it from being a wobble.")
                .defineInRange("response", 11.0, 1.0, 40.0);

        CAMERA_DAMPING = b
                .comment("Damping ratio. 1.0 is critical damping: fastest possible approach with zero",
                        "overshoot, which is the mathematical definition of crisp without wobble.",
                        "Below 1 rings. Above 1 goes soggy.")
                .defineInRange("damping", 1.0, 0.4, 2.0);

        CAMERA_PITCH_RESPONSE = b
                .comment("How much of the pitch component of the tilt to actually apply, 0..1.",
                        "Roll is well tolerated by the human vestibular system; pitch is what makes people",
                        "queasy. Damping only pitch keeps the bank readable while cutting the nausea, and it",
                        "is why this does not feel like the deck-locked version. Yaw is dropped entirely.")
                .defineInRange("pitch_response", 0.55, 0.0, 1.0);

        CAMERA_DECK_LEAN = b
                .comment("Small blend from felt-up towards the deck normal, 0..1 - the proprioceptive hint",
                        "that tells you which way the floor is. Keep it small: this is the term that, at 1.0,",
                        "would reproduce exactly the deck-locked 'glued to the plane' feel.")
                .defineInRange("deck_lean", 0.25, 0.0, 1.0);

        CAMERA_MAX_TILT_DEG = b
                .comment("Hard cap on total tilt in degrees. 65 lets a drum roll you a long way while",
                        "stopping fully inverted views, which read as broken rather than as intense.")
                .defineInRange("max_tilt_deg", 65.0, 0.0, 90.0);

        CAMERA_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the camera may turn, deg/s. The spring handles ordinary motion;",
                        "this exists for the pathological case - a contraption that snaps 180 degrees in one",
                        "tick - so the camera leans over instead of whipping.")
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

        DEBUG_SMOOTHING_MS = b
                .comment("Half-life of the arrow smoothing in milliseconds. Small on purpose: the arrows",
                        "must stop flickering without lying about when a force actually arrived.")
                .defineInRange("smoothing_ms", 70, 0, 1000);

        b.pop();

        SPEC = b.build();
    }

    private CfConfig() {
    }
}
