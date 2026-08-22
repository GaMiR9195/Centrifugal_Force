package dev.gamir.sable_cf;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config, and the only place a number lives. Commands write straight into these values and save,
 * so nothing needs a restart and nothing needs a second copy of the defaults.
 *
 * <h2>One flat number per subsystem</h2>
 *
 * <p>Each subsystem has exactly one strength knob on the subsystem itself: {@code strength} is what
 * {@code /sable_cf air_resistance 1.4} writes. No reference speeds, no exponents, no shaping
 * constants to discover - a knob you have to combine with another knob in your head is not a knob
 * you can tune. The shaping constants still exist; they are fixed below, where they can be read
 * once instead of tuned forever.</p>
 *
 * <h2>Units</h2>
 *
 * <p>Sable reports velocities in m/s (blocks per second), and Minecraft's gravity of
 * 0.08 blocks/tick^2 is 32 m/s^2 in those units - that is {@link #GRAVITY}. "1 g" anywhere in this
 * mod means 32, not 9.81.</p>
 *
 * <h2>Why COMMON and not CLIENT</h2>
 *
 * <p>The body orientation is read by Sable's collision path, and the commands that write these
 * values have to work on a dedicated server. Both sides derive the orientation from the same pose
 * with the same constants, so a common config is what keeps them from disagreeing.</p>
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

    /** Air speed, m/s, at which drag reaches one gravity at {@code strength = 1}. */
    private static final double AIR_REFERENCE_SPEED = 40.0;

    /**
     * Exponent of the drag law. Real aerodynamic drag is quadratic; this is 1.35.
     *
     * <p>Quadratic drag does almost nothing until a knee and then removes all control at once,
     * which reads as "nothing, nothing, thrown off". At 1.35 the ramp is gradual enough that
     * sliding is a state you can steer against for a few seconds. Still superlinear, so speed
     * still genuinely matters.</p>
     */
    private static final double AIR_EXPONENT = 1.35;

    /** Soft ceiling on drag, in g. Past this the curve compresses instead of continuing. */
    private static final double AIR_SOFT_CAP_G = 2.2;

    /**
     * Sharpness of the blend between contacting faces. Lower is a rounder floor-to-wall ramp,
     * higher approaches a hard switch.
     */
    public static final double SURFACE_BLEND_SHARPNESS = 6.0;

    /** Half-life, seconds, of the body orientation chasing its target. */
    public static final double BODY_HALF_LIFE = 0.16;

    /**
     * Half-life, seconds, of the low-pass on the frame acceleration.
     *
     * <p>Not optional. The frame acceleration is a <i>second</i> difference of a pose that arrives
     * over the network and is interpolated on the way in, so its raw value carries several m/s^2 of
     * pure noise even when the contraption is doing nothing at all. Unfiltered, that noise is what
     * made a player standing still on an ordinary moving sub-level get nudged and tipped.</p>
     */
    public static final double FRAME_ACCEL_HALF_LIFE = 0.10;

    /**
     * Frame acceleration below this, in g, is treated as exactly zero.
     *
     * <p>The companion to the filter above: filtering makes the noise small, and this makes it
     * nothing. An ordinary platform accelerating up to cruise never reaches it, so standing on a
     * moving deck is bit-for-bit vanilla. It is a dead zone rather than a threshold - the value is
     * faded in above it, so there is no step at the boundary.</p>
     */
    public static final double FRAME_ACCEL_DEADZONE_G = 0.16;

    /** Frame acceleration, in g, at which the dead-zone fade has finished. */
    public static final double FRAME_ACCEL_FULL_G = 0.34;

    /**
     * Rotation rate, rad/s, below which nothing rotation-specific happens: no wall attach, no
     * outward slip, no rim climb.
     *
     * <p>These three are explicitly features of a <i>spinning</i> ride. A lift, a drawbridge or a
     * ship under way is not spinning, and must not be able to trip them however it is being
     * shoved about.</p>
     */
    public static final double SPIN_DEADZONE = 0.15;

    /** Rotation rate, rad/s, at which the rotation-specific features are fully faded in. */
    public static final double SPIN_FULL = 0.45;

    /** cos of the angle past which a contact face counts as a wall rather than a slope. */
    public static final double WALL_COSINE = 0.55;

    public static final ModConfigSpec SPEC;

    // ---------------------------------------------------------------- rotating frame

    public static final ModConfigSpec.BooleanValue CENTRIFUGAL_ENABLED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue CORIOLIS_STRENGTH;
    public static final ModConfigSpec.DoubleValue MAX_ACCEL_G;

    // ---------------------------------------------------------------- air resistance

    public static final ModConfigSpec.BooleanValue AIR_ENABLED;
    public static final ModConfigSpec.DoubleValue AIR_STRENGTH;

    // ---------------------------------------------------------------- grip and attach

    public static final ModConfigSpec.BooleanValue GRIP_ENABLED;
    public static final ModConfigSpec.DoubleValue GRIP_STRENGTH;
    public static final ModConfigSpec.DoubleValue GRIP_BRACE_BONUS;
    public static final ModConfigSpec.DoubleValue GRIP_MIN_PRESS_G;
    public static final ModConfigSpec.DoubleValue GRIP_FULL_PRESS_G;
    public static final ModConfigSpec.DoubleValue ATTACH_PRESS_G;
    public static final ModConfigSpec.DoubleValue ATTACH_RELEASE_G;
    public static final ModConfigSpec.DoubleValue ATTACH_SHARE;
    public static final ModConfigSpec.DoubleValue ATTACH_ADHESION_G;

    // ---------------------------------------------------------------- outward slip

    public static final ModConfigSpec.BooleanValue SLIP_ENABLED;
    public static final ModConfigSpec.DoubleValue SLIP_STRENGTH;
    public static final ModConfigSpec.DoubleValue SLIP_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue RIM_CLIMB_G;
    public static final ModConfigSpec.DoubleValue RIM_CLIMB_SPEED;

    // ---------------------------------------------------------------- release

    public static final ModConfigSpec.BooleanValue RELEASE_ENABLED;
    public static final ModConfigSpec.DoubleValue RELEASE_DECEL_G;
    public static final ModConfigSpec.DoubleValue RELEASE_MIN_SPEED;

    // ---------------------------------------------------------------- hitbox

    public static final ModConfigSpec.BooleanValue HITBOX_ENABLED;
    public static final ModConfigSpec.DoubleValue HITBOX_AMOUNT;

    // ---------------------------------------------------------------- camera

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ModConfigSpec.DoubleValue CAMERA_AMOUNT;
    public static final ModConfigSpec.DoubleValue CAMERA_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DAMPING;
    public static final ModConfigSpec.DoubleValue CAMERA_SMOOTHING;
    public static final ModConfigSpec.DoubleValue CAMERA_DEADBAND_DEG;
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
                        "what it would have done. 0.3 keeps the flavour without the fight.")
                .defineInRange("coriolis_strength", 0.3, 0.0, 2.0);

        MAX_ACCEL_G = b
                .comment("Hard clamp on everything this mod adds, in g (1 g = 32 m/s^2).",
                        "A safety rail, not a tuning knob: a contraption that teleports produces one tick",
                        "of enormous bogus acceleration, and without this you get launched into orbit.")
                .defineInRange("max_accel_g", 8.0, 0.5, 64.0);

        b.pop();

        b.comment("Air drag on the player.",
                        "Measured against the air the sub-level carries with it, NOT against the world.",
                        "That distinction is the whole point: a deck cruising at 25 m/s is not a 25 m/s",
                        "headwind for someone standing on it, and treating it as one is what used to shove",
                        "players around on perfectly ordinary moving platforms. What survives the",
                        "subtraction is exactly what should: the deck's ROTATION (omega x r) and your own",
                        "walking. So a spinner still tries to peel you off and a lift does nothing at all.",
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

        b.comment("Footing: how much sideways load your feet hold before you slide, and what it takes",
                        "for a surface to grab you at all. This is the whole stand / slide / swept-off",
                        "decision, plus the deliberate wall-attach that makes a drum ride work.")
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
                .comment("Press, in g, below which a surface is not holding you and the body stays",
                        "upright. Bottom of the partial-tilt ramp.")
                .defineInRange("min_press_g", 0.2, 0.0, 4.0);

        GRIP_FULL_PRESS_G = b
                .comment("Press, in g, at which the body is fully aligned with the surface - standing on",
                        "a wall as if it were the floor. Top of the partial-tilt ramp.",
                        "1.2 on purpose rather than something dramatic: a washing-machine ride is not",
                        "going to spin at 4 g, and the effect has to be reachable to be a feature.")
                .defineInRange("full_press_g", 1.2, 0.1, 16.0);

        ATTACH_PRESS_G = b
                .comment("Press, in g, at which touching a WALL sideways latches you to it.",
                        "Answers 'when should the drum grab me': when the ride is genuinely pinning you,",
                        "not merely when you happened to walk into a wall.")
                .defineInRange("attach_press_g", 0.75, 0.05, 16.0);

        ATTACH_RELEASE_G = b
                .comment("Press, in g, below which an attached player lets go again. Deliberately lower",
                        "than attach_press_g: that gap is hysteresis, and without it you would flicker",
                        "between stuck and free every tick right at the threshold.",
                        "You can also always let go on purpose - jump, or release sneak while sliding.")
                .defineInRange("release_press_g", 0.45, 0.0, 16.0);

        ATTACH_SHARE = b
                .comment("Fraction of the press into a wall that must come from the RIDE rather than from",
                        "you, 0..1, before it can latch. This is the 'only in the right scenarios' rule:",
                        "a drum flinging you outward passes it easily, while running at a wall on a calm",
                        "contraption cannot, so ordinary walls stay ordinary and remain bounce-off-able.")
                .defineInRange("attach_share", 0.6, 0.0, 1.0);

        ATTACH_ADHESION_G = b
                .comment("Extra acceleration into the surface while attached, in g. Small on purpose.",
                        "Sable resolves entity/sub-level contact in a limited number of substeps, so a",
                        "body that is exactly touching a moving wall drifts a hair off it and back every",
                        "tick, and 'stuck to the drum' becomes a coin flip. This keeps contact closed.",
                        "It is not what holds you up - that is friction. Raising it will not help you",
                        "stick; it will only make letting go feel sticky.")
                .defineInRange("adhesion_g", 0.35, 0.0, 4.0);

        b.pop();

        b.comment("Outward slip: the deliberate drift along the centrifugal direction.",
                        "In a drum you should ease from the middle out to the rim and end up leaning on",
                        "the lip - not because the floor is tilted, but because you are being flung.",
                        "Rotation-gated, so nothing here can happen on a sub-level that is not spinning.")
                .push("slip");

        SLIP_ENABLED = b
                .comment("/sable_cf slip enable|disable")
                .define("enabled", true);

        SLIP_STRENGTH = b
                .comment("The one knob. /sable_cf slip <value>",
                        "Fraction of the surface-tangential centrifugal load that is allowed past friction",
                        "as outward creep. 0.35 is a slow, resistible slide you can walk against; 1.0 is",
                        "an ice rink pointing outward. 0 removes the creep and leaves plain friction.")
                .defineInRange("strength", 0.35, 0.0, 2.0);

        SLIP_MAX_SPEED = b
                .comment("Cap on the outward creep, m/s. It is a drift, not a launch: above this the",
                        "creep stops adding and only real physics moves you further out.")
                .defineInRange("max_speed", 3.2, 0.1, 20.0);

        RIM_CLIMB_G = b
                .comment("Centrifugal load, in g, at which you can climb the lip you are pressed against.",
                        "Explicitly NOT driven by the tilt of the physics: what happens is that once the",
                        "ride is throwing you outward this hard, an outward-facing obstruction is treated",
                        "as climbable, exactly like a step. A rider in a real rotor walks up the wall for",
                        "the same reason. Below this the lip is a wall and stops you.")
                .defineInRange("rim_climb_g", 1.35, 0.2, 16.0);

        RIM_CLIMB_SPEED = b
                .comment("How fast the rim climb carries you up the lip, m/s. Kept near walking pace so",
                        "it reads as climbing rather than as being fired out of the ride.")
                .defineInRange("rim_climb_speed", 2.6, 0.1, 12.0);

        b.pop();

        b.comment("Release: what happens when the contraption stops but you do not.",
                        "A lift rising with you on it jams its edge against a frame. The sub-level stops",
                        "in one tick; you were doing 8 m/s upward a moment ago, and the honest outcome is",
                        "that you keep going - straight up, out through the middle.",
                        "Only ever applies to a player who was ALREADY attached. Nothing here can make",
                        "you bounce off a contraption you merely touched.")
                .push("release");

        RELEASE_ENABLED = b
                .comment("/sable_cf release enable|disable")
                .define("enabled", true);

        RELEASE_DECEL_G = b
                .comment("How hard the deck has to stop, in g, to throw you clear. High on purpose:",
                        "this must fire for a genuine hard stop and never for normal manoeuvring, so it",
                        "is set well above anything a contraption does while it is still moving.")
                .defineInRange("decel_g", 9.0, 1.0, 64.0);

        RELEASE_MIN_SPEED = b
                .comment("How fast the deck had to be carrying you, m/s, for a stop to count. Stopping",
                        "from walking pace should not launch anybody.")
                .defineInRange("min_speed", 3.5, 0.5, 40.0);

        b.pop();

        b.comment("Rotating the player's body with the surface.",
                        "This supplies an ORIENTATION and nothing else. Sable's own collision path builds",
                        "an oriented box from it and does SAT against sub-level geometry, so the box is",
                        "genuinely turned rather than approximated - no widening, no wedging in corridors,",
                        "and no disagreement with what you can see.")
                .push("hitbox");

        HITBOX_ENABLED = b
                .comment("/sable_cf hitbox enable|disable")
                .define("enabled", true);

        HITBOX_AMOUNT = b
                .comment("How much of the body tilt the collision orientation follows, 0..1.",
                        "/sable_cf hitbox <value>. 1.0 tracks the body exactly. Lower only if you want",
                        "the visual and camera lean without the body turning with it.")
                .defineInRange("amount", 1.0, 0.0, 1.0);

        b.pop();

        b.comment("Camera. Requires Aeronautics Camera Sync with addTiltSource() (api-beta, > 1.3.7).",
                        "Aimed at FELT gravity, not the deck plane - that single choice is the difference",
                        "between 'barely moves on a gentle list' and 'rolls all the way over inside a",
                        "drum', with no threshold anywhere. Tuned gentle: it should follow softly and",
                        "still let you FEEL a change of direction.")
                .push("camera");

        CAMERA_ENABLED = b
                .comment("/sable_cf camera enable|disable")
                .define("enabled", true);

        CAMERA_AMOUNT = b
                .comment("Overall tilt amount. The one knob. /sable_cf camera <value>",
                        "0 keeps the camera level while everything else still works.")
                .defineInRange("amount", 1.0, 0.0, 2.0);

        CAMERA_RESPONSE = b
                .comment("Base spring frequency, rad/s. 4.5 settles in about 0.45 s - soft, not sluggish.",
                        "A change of direction raises it on its own; see jolt_gain. This is the resting",
                        "value, not the peak.")
                .defineInRange("response", 4.5, 1.0, 40.0);

        CAMERA_DAMPING = b
                .comment("Damping ratio. 1.05 is a shade past critical: the fastest approach that cannot",
                        "overshoot, with a little margin so a spike cannot make it ring.")
                .defineInRange("damping", 1.05, 0.4, 2.0);

        CAMERA_SMOOTHING = b
                .comment("Half-life, seconds, of the low-pass on the camera's TARGET (not on the camera).",
                        "Filtering the target rather than the output is what makes this gentle without",
                        "making it late: the spring still chases hard, it just is not handed a jittering",
                        "goal to chase. Raise it if the view feels busy on a rough ride.")
                .defineInRange("smoothing", 0.12, 0.0, 1.0);

        CAMERA_DEADBAND_DEG = b
                .comment("Jitter dead zone, degrees. Target motion smaller than this does not move the",
                        "camera at all. This is the fix for micro-tremble: the felt-down vector is",
                        "reconstructed from a networked pose, so it is never perfectly still, and a",
                        "perfectly faithful camera therefore never stops twitching.",
                        "Applied as slop, not as a step - past the dead zone tracking is continuous, so",
                        "it cannot cause stair-stepping.")
                .defineInRange("deadband_deg", 0.7, 0.0, 10.0);

        CAMERA_JOLT_GAIN = b
                .comment("How much a CHANGE OF DIRECTION speeds the camera up. Driven by how fast felt-down",
                        "is swinging, which is precisely 'the centrifugal direction is changing' - the",
                        "thing you actually feel in your neck - plus a little of the sub-level's angular",
                        "acceleration for a sharp flick of the controls.",
                        "Not driven by the SIZE of the centrifugal vector: that is omega^2 * r, which grows",
                        "with radius and with perfectly steady spin, so it read large when nothing was",
                        "happening and small for a genuine flick near the axis.",
                        "0.7 is a light touch - present, not theatrical. The curve is x/(1+x), monotonic",
                        "and bounded, so it cannot spike.")
                .defineInRange("jolt_gain", 0.7, 0.0, 6.0);

        CAMERA_LOOP_SUPPRESSION = b
                .comment("How much to stop following felt-down once the sub-level is going all the way",
                        "round, 0..1. During a loop felt-down sweeps a full circle, and a camera that",
                        "tracks it honestly rolls 360 degrees - the single most nauseating thing a camera",
                        "can do, and not what a human does either: you keep your head with your body and",
                        "let the world go round you.",
                        "So above a sustained flip the target fades to the recent AVERAGE of felt-down,",
                        "which over a full revolution is stable. 0 restores the spinning behaviour.")
                .defineInRange("loop_suppression", 0.85, 0.0, 1.0);

        CAMERA_WALK_DAMPING = b
                .comment("How much to calm the camera while you are moving under your own power, 0..1.",
                        "Walking on a spinner changes your radius every tick, so the target moves even",
                        "though the ride is doing nothing new. That is a different signal from a bank and",
                        "has to be damped separately. Only ever damps - holding on in a bank is untouched.")
                .defineInRange("walk_damping", 0.6, 0.0, 1.0);

        CAMERA_PITCH_RESPONSE = b
                .comment("How much of the pitch component to apply, 0..1. Roll is well tolerated by the",
                        "human vestibular system; pitch is what makes people queasy. Yaw is dropped.")
                .defineInRange("pitch_response", 0.45, 0.0, 1.0);

        CAMERA_DECK_LEAN = b
                .comment("Small blend from felt-up towards the surface normal, 0..1 - the proprioceptive",
                        "hint that tells you which way the floor is. Keep it small: at 1.0 it reproduces",
                        "exactly the deck-locked 'glued to the plane' feel it exists to avoid.")
                .defineInRange("deck_lean", 0.2, 0.0, 1.0);

        CAMERA_MAX_TILT_DEG = b
                .comment("Hard cap on total tilt, degrees. 65 lets a drum roll you a long way while",
                        "stopping fully inverted views, which read as broken rather than as intense.")
                .defineInRange("max_tilt_deg", 65.0, 0.0, 90.0);

        CAMERA_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the camera may turn, deg/s. The spring handles ordinary motion;",
                        "this exists for the pathological case - a contraption that snaps 180 degrees in",
                        "one tick - so the camera leans over instead of whipping.")
                .defineInRange("slew_deg_per_s", 200.0, 30.0, 1440.0);

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
                        "force is still readable as a direction. Below the dead zone nothing is drawn.")
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
     * Drag acceleration magnitude, m/s^2, for a speed through the air in m/s.
     *
     * <p>Superlinear but not quadratic, and soft capped. Kept here rather than in the handler so
     * the shaping constants and the knob that scales them are on the same page of code.</p>
     */
    public static double dragMagnitude(final double airSpeed) {
        if (!(airSpeed > 0.0) || !Double.isFinite(airSpeed)) {
            return 0.0;
        }

        final double strength = AIR_STRENGTH.get();

        if (strength <= 0.0) {
            return 0.0;
        }

        final double raw = strength * Math.pow(airSpeed / AIR_REFERENCE_SPEED, AIR_EXPONENT);

        // x / (1 + x/cap) -> approaches cap instead of crossing it, with no corner at the knee.
        return (raw / (1.0 + raw / AIR_SOFT_CAP_G)) * GRAVITY;
    }

    /**
     * The partial-tilt ramp: 0 while barely pressed, 1 once firmly pressed, smooth in between.
     *
     * @param press normal load in m/s^2
     */
    public static double tiltFromPress(final double press) {
        return smoothstep(press, GRIP_MIN_PRESS_G.get() * GRAVITY, GRIP_FULL_PRESS_G.get() * GRAVITY);
    }

    /**
     * How much of the rotation-specific behaviour applies at this spin rate, 0..1.
     *
     * <p>Wall attach, outward slip and rim climb are features of a spinning ride. Gating them on
     * the spin rate is what guarantees that a lift, a drawbridge or a ship under way can never
     * trigger them no matter how it is being thrown about - which is a structural promise rather
     * than a tuning question.</p>
     *
     * @param spin magnitude of the sub-level's angular velocity, rad/s
     */
    public static double spinGate(final double spin) {
        return smoothstep(spin, SPIN_DEADZONE, SPIN_FULL);
    }

    /**
     * Dead-zone fade for the frame acceleration, 0..1.
     *
     * <p>Returns 0 for anything a plain moving platform produces, so "standing on a sub-level that
     * is simply travelling" is arithmetically identical to standing on the ground.</p>
     *
     * @param magnitude frame acceleration magnitude, m/s^2
     */
    public static double frameAccelGate(final double magnitude) {
        return smoothstep(magnitude, FRAME_ACCEL_DEADZONE_G * GRAVITY, FRAME_ACCEL_FULL_G * GRAVITY);
    }

    /** Smoothstep with zero slope at both ends, so nothing snaps on entry or exit. */
    public static double smoothstep(final double value, final double low, final double high) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }

        if (!(high > low)) {
            return value >= high ? 1.0 : 0.0;
        }

        final double t = Math.min(1.0, Math.max(0.0, (value - low) / (high - low)));

        return t * t * (3.0 - 2.0 * t);
    }

    /** Frame-rate independent smoothing factor from a half-life in seconds. */
    public static double smoothingAlpha(final double halfLifeSeconds, final double dt) {
        if (!(halfLifeSeconds > 0.0) || !(dt > 0.0)) {
            return 1.0;
        }

        return Math.min(1.0, Math.max(0.0, 1.0 - Math.pow(2.0, -dt / halfLifeSeconds)));
    }

    private CfConfig() {
    }
}
