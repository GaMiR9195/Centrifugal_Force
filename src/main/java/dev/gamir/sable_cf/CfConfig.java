package dev.gamir.sable_cf;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config, and the only place a number lives. Commands write straight into these and save.
 *
 * <h2>Why there is no longer a 'slip'</h2>
 *
 * <p>There were two knobs for one idea, and the overlap was not cosmetic: switching air resistance
 * off did not stop you sliding off, because the other one was still pushing. Peeling a player off a
 * contraption is ONE mechanism and it belongs to {@code air_resistance} - the headwind that drags
 * you and the outward creep that walks you to the rim are the same statement about the same ride.
 * The wall CLIMB was never part of that idea and has its own {@code wall} section now, because it
 * is the opposite force: the one that keeps you on.</p>
 *
 * <h2>Units</h2>
 *
 * <p>Sable reports m/s. Minecraft gravity is 0.08 blocks/tick^2 = 32 m/s^2, which is
 * {@link #GRAVITY}. "1 g" here means 32, not 9.81.</p>
 */
public final class CfConfig {

    /** Minecraft gravity in Sable's units. Not 9.81. */
    public static final double GRAVITY = 32.0;

    // ---------------------------------------------------------------- drag shaping

    private static final double AIR_REFERENCE_SPEED = 40.0;

    /** Superlinear, not quadratic. Quadratic drag reads as "nothing, nothing, thrown off". */
    private static final double AIR_EXPONENT = 1.35;

    private static final double AIR_SOFT_CAP_G = 2.2;

    // ---------------------------------------------------------------- body orientation

    /** Blend sharpness between contacting faces for the PHYSICS normal. Low = rounder ramp. */
    public static final double SURFACE_BLEND_SHARPNESS = 6.0;

    /** Half-life, s, of the body orientation chasing its target. Drives the camera only. */
    public static final double BODY_HALF_LIFE = 0.16;

    /** Cap on how fast the body may turn, deg/s. */
    public static final double BODY_SLEW_DEG_PER_S = 240.0;

    /**
     * Half-life, s, of the COLLISION orientation chasing the body. Slower than the body on purpose.
     *
     * <p>Sable turns the collision box about the player's EYE, not their feet, so a lean of A
     * degrees sweeps the feet sideways by {@code 2 * 1.62 * sin(A/2)} blocks. Move that fast beside
     * a wall and SAT reports a metre-deep penetration, which comes straight back as a metre-long
     * shove. Slow is not politeness, it is the difference between leaning and being fired through
     * the wall.</p>
     */
    public static final double HITBOX_HALF_LIFE = 0.30;

    /** Cap on how fast the collision box may turn, deg/s. */
    public static final double HITBOX_SLEW_DEG_PER_S = 70.0;

    /** How far to shrink the body box before testing whether a lean fits, blocks. */
    public static final double CLEARANCE_SHRINK = 0.06;

    /**
     * Half-life, s, of the low-pass on the frame acceleration. Applied in the SUB-LEVEL's frame.
     *
     * <p>A steady spin is constant in the deck's frame but rotating in the world, and a first-order
     * lag on a rotating vector rotates it backwards - about 16 degrees at 2.5 rad/s - which is a
     * permanent sideways shove out of nothing.</p>
     */
    public static final double FRAME_ACCEL_HALF_LIFE = 0.10;

    /** Frame acceleration below this, in g, is exactly zero. Anti-noise only. */
    public static final double FRAME_ACCEL_DEADZONE_G = 0.06;

    public static final double FRAME_ACCEL_FULL_G = 0.14;

    /**
     * Spin rate, rad/s, below which nothing rotation-specific happens: no attach, no creep, no
     * climb. A gate on angular velocity itself, not on a force a translation could also produce.
     */
    public static final double SPIN_DEADZONE = 0.15;

    public static final double SPIN_FULL = 0.45;

    /** cos of the angle past which a contact is REPORTED as a wall. Display only. */
    public static final double WALL_COSINE = 0.55;

    public static final double DECK_RELATIVE_HALF_LIFE = 0.08;

    /**
     * How far felt gravity must have left world gravity before the body leans, as {@code 1-feltUp.y}.
     *
     * <p>This replaced gating the lean on the ride's SHARE of the press, and the difference is why
     * wall entry never worked. The share is near zero on the FLOOR of a drum - correctly, a
     * horizontal centrifugal vector presses you into a floor by nothing - so you walked to the wall
     * bolt upright right up until the moment you needed not to be. Felt gravity has no blind spot:
     * it is already leaning outward while you are still on the floor.</p>
     *
     * <p>The old guarantee survives. A sub-level that merely travels has felt gravity equal to
     * world gravity by construction, so this is identically zero for lifts, ships and drawbridges
     * however hard they accelerate.</p>
     */
    public static final double TILT_DEVIATION_LOW = 0.04;

    public static final double TILT_DEVIATION_HIGH = 0.35;

    /** How well the face you touch must oppose felt-down before you stand on it. */
    public static final double TILT_ALIGN_LOW = 0.15;

    public static final double TILT_ALIGN_HIGH = 0.70;

    public static final double CLIMB_SHARE_LOW = 0.30;

    public static final double CLIMB_SHARE_HIGH = 0.65;

    /**
     * How fast the player may already be closing on a surface, m/s, before the mod stops pressing
     * them into it harder.
     *
     * <p>Sable resolves contact positionally and never zeroes {@code deltaMovement} against a
     * sub-level wall, so an into-surface acceleration applied every tick with nothing watching the
     * result accumulates into a stored velocity. It stays invisible until one tick resolves the
     * whole penetration at once and fires you through the wall.</p>
     */
    public static final double PRESS_MAX_SPEED = 6.0;

    /** Acceleration, m/s^2, the walk keys get along a surface you are pinned to. */
    public static final double WALL_WALK_ACCEL = 26.0;

    /** Hard ceiling, m/s, on a release velocity. A sanity rail. */
    public static final double RELEASE_MAX_SPEED = 40.0;

    /** Below this cosine, world up and the target are antiparallel and the axis must be supplied. */
    public static final double ANTIPARALLEL_COSINE = -0.995;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue CENTRIFUGAL_ENABLED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue CORIOLIS_STRENGTH;
    public static final ModConfigSpec.DoubleValue MAX_ACCEL_G;

    public static final ModConfigSpec.BooleanValue AIR_ENABLED;
    public static final ModConfigSpec.DoubleValue AIR_STRENGTH;
    public static final ModConfigSpec.DoubleValue AIR_SLIDE;
    public static final ModConfigSpec.DoubleValue AIR_SLIDE_MAX_SPEED;

    public static final ModConfigSpec.BooleanValue GRIP_ENABLED;
    public static final ModConfigSpec.DoubleValue GRIP_STRENGTH;
    public static final ModConfigSpec.DoubleValue GRIP_BRACE_BONUS;
    public static final ModConfigSpec.DoubleValue GRIP_MIN_PRESS_G;
    public static final ModConfigSpec.DoubleValue GRIP_FULL_PRESS_G;
    public static final ModConfigSpec.DoubleValue GRIP_SLIDE_CAP_G;
    public static final ModConfigSpec.DoubleValue ATTACH_PRESS_G;
    public static final ModConfigSpec.DoubleValue ATTACH_RELEASE_G;
    public static final ModConfigSpec.DoubleValue ATTACH_SHARE;
    public static final ModConfigSpec.DoubleValue ATTACH_ADHESION_G;

    public static final ModConfigSpec.BooleanValue WALL_ENABLED;
    public static final ModConfigSpec.DoubleValue WALL_STRENGTH;
    public static final ModConfigSpec.DoubleValue WALL_PRESS_G;
    public static final ModConfigSpec.DoubleValue WALL_MAX_SPEED;

    public static final ModConfigSpec.BooleanValue RELEASE_ENABLED;
    public static final ModConfigSpec.DoubleValue RELEASE_DECEL_G;
    public static final ModConfigSpec.DoubleValue RELEASE_MIN_SPEED;

    public static final ModConfigSpec.BooleanValue HITBOX_ENABLED;
    public static final ModConfigSpec.DoubleValue HITBOX_AMOUNT;
    public static final ModConfigSpec.DoubleValue HITBOX_MAX_DEG;

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ModConfigSpec.DoubleValue CAMERA_AMOUNT;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAN;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAN_MAX_DEG;
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
                        "|a| = omega^2 * r: at 5 blocks radius you need ~2.5 rad/s (~24 rpm) to match",
                        "gravity, which is about what a real rotor ride spins at.",
                        "On the FLOOR of a drum the centrifugal vector is horizontal, so it presses you",
                        "into the floor by nothing and only pushes you outward. Sliding out to the wall",
                        "is the ride, not a bug; the press appears once you reach the wall.")
                .push("centrifugal_force");

        CENTRIFUGAL_ENABLED = b.comment("/sable_cf centrifugal_force enable|disable")
                .define("enabled", true);

        CENTRIFUGAL_STRENGTH = b
                .comment("The one knob. 1.0 is the physical value. /sable_cf centrifugal_force <value>")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        CORIOLIS_STRENGTH = b
                .comment("Scale on -2 omega x v, the term driven by your own walking. Full strength is",
                        "correct but reads as a mysterious sideways pull, and Sure Footing already",
                        "rotates your velocity with the frame, which cancels most of it.")
                .defineInRange("coriolis_strength", 0.3, 0.0, 2.0);

        MAX_ACCEL_G = b
                .comment("Hard clamp on everything this mod adds, in g. A safety rail, not a knob: a",
                        "contraption that teleports makes one tick of enormous bogus acceleration.")
                .defineInRange("max_accel_g", 8.0, 0.5, 64.0);

        b.pop();

        b.comment("Getting peeled off. The ONLY thing in the mod that slides you off a contraption.",
                        "There used to be a second knob called 'slip' doing half the same job, so",
                        "switching air resistance off did not stop you sliding. It is one idea, so it is",
                        "one section, in two parts:",
                        "  strength - the headwind. Pinned in a drum you are carried through still air at",
                        "             omega x r, and that is what makes holding on a thing you DO.",
                        "             Measured against the air the sub-level carries: the rigid",
                        "             translation is subtracted, so a deck cruising at 25 m/s is not a gale",
                        "             for someone standing on it. Rotation survives, translation cancels.",
                        "  slide    - the outward creep. Friction is symmetric, so once it holds you it",
                        "             holds you exactly where you are and the ride never moves you to the",
                        "             rim. This is what walks you out to the wall.",
                        "Set this to 0 and wall walking becomes effortless. That is the difficulty knob.")
                .push("air_resistance");

        AIR_ENABLED = b
                .comment("/sable_cf air_resistance enable|disable. Off stops BOTH the headwind and the",
                        "creep, so it really does mean 'nothing peels me off'.")
                .define("enabled", true);

        AIR_STRENGTH = b.comment("The one knob. /sable_cf air_resistance <value>")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        AIR_SLIDE = b
                .comment("Fraction of the surface-tangential centrifugal load let past friction as",
                        "outward creep. 0.30 is a slow drift you can walk against. 0 removes it.")
                .defineInRange("slide", 0.30, 0.0, 2.0);

        AIR_SLIDE_MAX_SPEED = b
                .comment("Cap on the creep, m/s. It fades as you approach this, so it settles at a",
                        "terminal drift instead of clipping at one.")
                .defineInRange("slide_max_speed", 2.2, 0.1, 20.0);

        b.pop();

        b.comment("Footing: how much sideways load your feet hold before you slide.",
                        "Raised hard from the old 1.15. At 1.15 any contraption that turned briskly beat",
                        "friction, and because the excess was applied every tick with nothing observing",
                        "the resulting slide, one turn started a slide that never stopped. Two separate",
                        "fixes: grip decides WHETHER you move, slide_cap_g and the speed limit decide how",
                        "hard and how far - so a sharp turn shoves you instead of evicting you.")
                .push("grip");

        GRIP_ENABLED = b.comment("/sable_cf grip enable|disable. Off means everything slides.")
                .define("enabled", true);

        GRIP_STRENGTH = b
                .comment("The one knob: static friction coefficient. /sable_cf grip <value>",
                        "2.2 is well above a realistic boot-on-metal 0.85, on purpose. This is the number",
                        "that decides whether a deck yanking sideways takes you with it, and being thrown",
                        "off something you were standing on is the least fun outcome available.")
                .defineInRange("strength", 2.2, 0.0, 8.0);

        GRIP_BRACE_BONUS = b
                .comment("Friction multiplier while sneaking - the 'hold on' input.")
                .defineInRange("brace_bonus", 1.9, 1.0, 4.0);

        GRIP_MIN_PRESS_G = b
                .comment("Press, in g, below which a surface is not holding you. Bottom of the lean ramp.")
                .defineInRange("min_press_g", 0.2, 0.0, 4.0);

        GRIP_FULL_PRESS_G = b
                .comment("Press, in g, at which the surface holds you completely. Top of the lean ramp,",
                        "bottom of the wall-climb ramp. 0.8 rather than 1.0: a homemade drum will not",
                        "spin at rotor-ride speeds, and an effect you cannot reach is not a feature.")
                .defineInRange("full_press_g", 0.8, 0.1, 16.0);

        GRIP_SLIDE_CAP_G = b
                .comment("Most acceleration a slide may ever have, in g. The gameplay rail. Without it",
                        "the excess past friction is unbounded and a contraption snapping sideways does",
                        "not slide you, it deletes you from the deck.")
                .defineInRange("slide_cap_g", 1.1, 0.05, 16.0);

        ATTACH_PRESS_G = b
                .comment("Press, in g, at which touching a surface sideways latches you to it.")
                .defineInRange("attach_press_g", 0.45, 0.05, 16.0);

        ATTACH_RELEASE_G = b
                .comment("Press, in g, below which an attached player lets go. Lower than attach on",
                        "purpose: that gap is hysteresis. Jumping always releases.")
                .defineInRange("release_press_g", 0.25, 0.0, 16.0);

        ATTACH_SHARE = b
                .comment("Fraction of the press into a surface that must come from the RIDE rather than",
                        "gravity before it can latch. The 'only in the right scenarios' rule, and what",
                        "makes the check safe on any orientation: an ordinary floor is pressed by gravity",
                        "so its share is near zero and it can never latch, while the inside of a drum is",
                        "near one whether it is beside you, below you or above you at the top of a loop.")
                .defineInRange("attach_share", 0.45, 0.0, 1.0);

        ATTACH_ADHESION_G = b
                .comment("Extra acceleration into the surface while attached, in g. Small on purpose:",
                        "Sable resolves contact in a few substeps, so a body exactly touching a moving",
                        "wall drifts off it and back every tick. This keeps the contact closed. It is not",
                        "what holds you up - that is friction.")
                .defineInRange("adhesion_g", 0.35, 0.0, 4.0);

        b.pop();

        b.comment("Wall walking. The mod is fundamentally about this.",
                        "Two things must be true before a wall behaves like a floor, and only one of them",
                        "used to be here:",
                        "  1. the along-surface pull of gravity is cancelled, in proportion to how hard",
                        "     the RIDE presses you in - a rider in a real rotor walks up the drum because",
                        "     press gives their boots the friction to beat gravity;",
                        "  2. the walk keys actually drive you ALONG the surface. Without this the first",
                        "     one only buys the right to hover: you press W, Minecraft pushes you",
                        "     horizontally into the wall, and nothing happens.",
                        "Rotation-gated and share-gated, so an ordinary wall is still a wall.")
                .push("wall");

        WALL_ENABLED = b.comment("/sable_cf wall enable|disable").define("enabled", true);

        WALL_STRENGTH = b
                .comment("The one knob. /sable_cf wall <value>. Scales the gravity cancellation and the",
                        "walk drive together, because they are one mechanic.")
                .defineInRange("strength", 1.0, 0.0, 2.0);

        WALL_PRESS_G = b
                .comment("Centrifugal press, in g, at which the surface handles exactly like a floor.",
                        "Ramps from grip.full_press_g to here, and the WIDTH is the point: press on a real",
                        "drum wanders by a few m/s^2 tick to tick, so a narrow band is a switch with noise",
                        "on it and the assist snaps on and off several times a second.")
                .defineInRange("press_g", 1.3, 0.2, 16.0);

        WALL_MAX_SPEED = b
                .comment("How fast the assist may carry you along the surface, m/s.")
                .defineInRange("max_speed", 3.4, 0.1, 12.0);

        b.pop();

        b.comment("Release: the contraption stops but you do not. A lift rising with you on it jams",
                        "against a frame; you were doing 8 m/s upward, so you keep going out through the",
                        "middle. Only ever applies to a player who was ALREADY attached.")
                .push("release");

        RELEASE_ENABLED = b.comment("/sable_cf release enable|disable").define("enabled", true);

        RELEASE_DECEL_G = b
                .comment("How hard the deck must stop, in g, to throw you clear. High on purpose.")
                .defineInRange("decel_g", 9.0, 1.0, 64.0);

        RELEASE_MIN_SPEED = b
                .comment("How fast the deck had to be carrying you, m/s, for a stop to count.")
                .defineInRange("min_speed", 3.5, 0.5, 40.0);

        b.pop();

        b.comment("Rotating the player's collision box.",
                        "Sable already snaps the box YAW to the sub-level grid by itself - that is the",
                        "Sure Footing behaviour, square to the contraption rather than to the world, and",
                        "it costs this mod nothing. This section is only the LEAN on top of it.",
                        "Read max_deg before changing anything. Sable turns the box about the player's",
                        "EYE, not their feet, so a lean of A degrees sweeps the feet sideways by",
                        "2 * 1.62 * sin(A/2) blocks: 0.28 at 10 deg, 0.97 at 35, 2.3 at 90. Beside a wall",
                        "that displacement IS a penetration, SAT returns it as a minimum translation",
                        "vector, and Sable's near-vertical branch redirects the whole length along your",
                        "body up. That is exactly 'I tried to get on the wall and it spat me through it'.",
                        "So the lean is capped, slewed slowly, and clearance-tested against real voxels",
                        "before it is handed over - it can never rotate into geometry.",
                        "You do not need a big lean to stand on a wall: an upright box pressed against one",
                        "occupies free space. What makes it FEEL like standing is the camera and the wall",
                        "section, not the box.")
                .push("hitbox");

        HITBOX_ENABLED = b
                .comment("/sable_cf hitbox enable|disable. Off keeps Sable's grid yaw and loses only the",
                        "lean.")
                .define("enabled", true);

        HITBOX_AMOUNT = b
                .comment("How much of the body lean the box follows, 0..1, before the cap.",
                        "/sable_cf hitbox <value>")
                .defineInRange("amount", 1.0, 0.0, 1.0);

        HITBOX_MAX_DEG = b
                .comment("Hard cap on the collision lean, degrees. THE safety number in this mod.",
                        "25 keeps the worst-case foot sweep near 0.7 blocks, which the clearance test can",
                        "still refuse cleanly. Raise it and you trade 'my body looks right' against 'I get",
                        "flung when I lean near geometry'. The eye pivot is upstream ask #2 in",
                        "docs/UPSTREAM.md and this cap disappears the day it becomes a feet pivot.")
                .defineInRange("max_deg", 25.0, 0.0, 180.0);

        b.pop();

        b.comment("Camera. Requires Aeronautics Camera Sync with addTiltSource().",
                        "Aimed at the BODY orientation plus a small CAPPED lean towards felt gravity.",
                        "The cap is the fix for 'it heels over violently on turns'. Leaning towards felt",
                        "gravity is right, but the angle it asks for is unbounded: a brisk turn produces",
                        "several g sideways and asks for 60-70 degrees of roll, which is nobody's idea of",
                        "a bank. lean_max_deg makes that a fixed small readable tilt while leaving the",
                        "RESPONSE - how fast it gets there - untouched, which is the part that actually",
                        "reads as reactive.")
                .push("camera");

        CAMERA_ENABLED = b.comment("/sable_cf camera enable|disable").define("enabled", true);

        CAMERA_AMOUNT = b
                .comment("Overall tilt amount. The one knob. /sable_cf camera <value>. 0 keeps the view",
                        "level while everything else still works.")
                .defineInRange("amount", 1.0, 0.0, 2.0);

        CAMERA_LEAN = b
                .comment("Fraction of the angle to felt gravity the view leans on top of following the",
                        "body, 0..1, then capped by lean_max_deg. This is the term that makes a change of",
                        "direction felt when you are NOT pinned, so it should not be zero.")
                .defineInRange("lean", 0.35, 0.0, 1.0);

        CAMERA_LEAN_MAX_DEG = b
                .comment("Hard cap on that lean, degrees. Raise if turns feel numb, lower if they feel",
                        "sickening. It does NOT limit standing on a wall - that comes from the body and is",
                        "bounded by max_tilt_deg instead.")
                .defineInRange("lean_max_deg", 12.0, 0.0, 90.0);

        CAMERA_RESPONSE = b
                .comment("Base spring frequency, rad/s. 4.5 settles in about 0.45 s.")
                .defineInRange("response", 4.5, 1.0, 40.0);

        CAMERA_DAMPING = b
                .comment("Damping ratio. 1.05 is a shade past critical: fastest approach with no",
                        "overshoot and a little margin so a spike cannot make it ring.")
                .defineInRange("damping", 1.05, 0.4, 2.0);

        CAMERA_SMOOTHING = b
                .comment("Half-life, s, of the low-pass on the camera's TARGET, not on the camera.",
                        "Filtering the target is what makes this gentle without making it late.")
                .defineInRange("smoothing", 0.14, 0.0, 1.0);

        CAMERA_DEADBAND_DEG = b
                .comment("Jitter dead zone, degrees, for micro-tremble. Applied as slop, not a step.")
                .defineInRange("deadband_deg", 0.7, 0.0, 10.0);

        CAMERA_JOLT_GAIN = b
                .comment("How much a CHANGE OF DIRECTION speeds the camera up. Speeds it UP - it does not",
                        "lean it further. That separation is deliberate: 'reactive' is a question about",
                        "time, and answering it with amplitude is what made turns nauseating.",
                        "Driven by how fast the target is swinging plus a little angular acceleration, so",
                        "it is large when the ride changes what it is doing and zero when it is doing the",
                        "same thing quickly. Curve is x/(1+x): monotonic, bounded, cannot spike.")
                .defineInRange("jolt_gain", 0.9, 0.0, 6.0);

        CAMERA_LOOP_SUPPRESSION = b
                .comment("How much to stop following the target once the sub-level goes all the way",
                        "round, 0..1. In a loop the body up sweeps a full circle and a camera that tracks",
                        "it honestly rolls 360 degrees - the most nauseating thing a camera can do, and",
                        "not what a human does: you keep your head with your body and let the world go",
                        "round you.")
                .defineInRange("loop_suppression", 0.85, 0.0, 1.0);

        CAMERA_WALK_DAMPING = b
                .comment("How much to calm the camera while you move across the deck, 0..1. Walking on a",
                        "spinner changes your radius every tick, so the target moves even though the ride",
                        "is doing nothing new. Only ever damps.")
                .defineInRange("walk_damping", 0.6, 0.0, 1.0);

        CAMERA_PITCH_RESPONSE = b
                .comment("How much of the pitch component to apply, 0..1. Roll is well tolerated; pitch",
                        "is what makes people queasy. Yaw is dropped entirely.")
                .defineInRange("pitch_response", 0.7, 0.0, 1.0);

        CAMERA_DECK_LEAN = b
                .comment("Small blend towards the geometric surface normal, 0..1 - the proprioceptive",
                        "hint that tells you which way the floor is. Keep it small.")
                .defineInRange("deck_lean", 0.08, 0.0, 1.0);

        CAMERA_MAX_TILT_DEG = b
                .comment("Hard cap on total tilt, degrees. 95 so being genuinely pinned to a drum wall -",
                        "or under the deck at the top of a loop - can be shown. Short of 180, where a",
                        "rotation vector has no defined axis.")
                .defineInRange("max_tilt_deg", 95.0, 0.0, 150.0);

        CAMERA_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the camera may turn, deg/s. For the pathological case - a",
                        "contraption that snaps 180 degrees in one tick.")
                .defineInRange("slew_deg_per_s", 200.0, 30.0, 1440.0);

        b.pop();

        b.comment("Debug overlay: flat camera-facing arrows with 2D triangular heads.").push("debug");

        DEBUG_OVERLAY = b.comment("/sable_cf debug_overlay").define("overlay", false);
        DEBUG_TEXT = b.comment("Also print the numbers in the top-left corner.").define("text", true);

        DEBUG_ACCEL_SCALE = b.comment("Blocks per m/s^2. 0.025 makes 1 g exactly 0.8 blocks long.")
                .defineInRange("accel_scale", 0.025, 0.001, 1.0);

        DEBUG_VELOCITY_SCALE = b.comment("Blocks per m/s.")
                .defineInRange("velocity_scale", 0.08, 0.001, 2.0);

        DEBUG_MIN_LENGTH = b
                .comment("Shorter arrows are drawn at this length so a small force is still readable as",
                        "a direction. Below the dead zone nothing is drawn.")
                .defineInRange("min_length", 0.28, 0.0, 4.0);

        DEBUG_MAX_LENGTH = b.comment("Arrows are never drawn longer than this.")
                .defineInRange("max_length", 3.5, 0.2, 32.0);

        DEBUG_ALPHA = b.comment("Arrow opacity, 0..1. Translucent on purpose.")
                .defineInRange("alpha", 0.7, 0.05, 1.0);

        DEBUG_SMOOTHING_MS = b.comment("Half-life of the arrow smoothing, milliseconds.")
                .defineInRange("smoothing_ms", 70, 0, 1000);

        b.pop();

        SPEC = b.build();
    }

    /** Drag acceleration magnitude, m/s^2, for a speed through the air in m/s. Soft capped. */
    public static double dragMagnitude(final double airSpeed) {
        if (!(airSpeed > 0.0) || !Double.isFinite(airSpeed)) {
            return 0.0;
        }

        final double strength = AIR_STRENGTH.get();

        if (strength <= 0.0) {
            return 0.0;
        }

        final double raw = strength * Math.pow(airSpeed / AIR_REFERENCE_SPEED, AIR_EXPONENT);

        // x / (1 + x/cap) approaches cap instead of crossing it, with no corner at the knee.
        return (raw / (1.0 + raw / AIR_SOFT_CAP_G)) * GRAVITY;
    }

    /** The lean ramp from press alone: 0 while barely pressed, 1 once firmly pressed. */
    public static double tiltFromPress(final double press) {
        return smoothstep(press, GRIP_MIN_PRESS_G.get() * GRAVITY, GRIP_FULL_PRESS_G.get() * GRAVITY);
    }

    /** How much of the wall assist the ride has earned, 0..1, from its share of the normal load. */
    public static double climbWeight(final double share) {
        return smoothstep(share, CLIMB_SHARE_LOW, CLIMB_SHARE_HIGH);
    }

    /**
     * How much rotation-specific behaviour applies at this spin rate, 0..1.
     *
     * <p>Attach, creep and climb are features of a SPINNING ride. Gating them on angular velocity
     * is what guarantees a lift or a ship under way can never trigger them however it is thrown
     * about - structural, not a tuning question.</p>
     */
    public static double spinGate(final double spin) {
        return smoothstep(spin, SPIN_DEADZONE, SPIN_FULL);
    }

    /** Dead-zone fade for the frame acceleration, 0..1. Anti-noise only. */
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
