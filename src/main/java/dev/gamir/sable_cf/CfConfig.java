package dev.gamir.sable_cf;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config, and the only place a number lives. Commands write straight into these values and save,
 * so nothing needs a restart and nothing needs a second copy of the defaults.
 *
 * <h2>One flat number per subsystem</h2>
 *
 * <p>Each subsystem has exactly one strength knob on itself: {@code strength} is what
 * {@code /sable_cf air_resistance 1.4} writes. A knob you have to combine with another knob in your
 * head is not a knob you can tune, so the shaping constants are fixed below where they can be read
 * once instead of tuned forever.</p>
 *
 * <h2>Why there is no longer a 'slip'</h2>
 *
 * <p>There were two knobs for one idea, and the overlap was not cosmetic - it meant switching air
 * resistance off did not stop you sliding off, because the other one was still pushing. Peeling a
 * player off a contraption is <b>one</b> mechanism and it belongs to {@code air_resistance}: the
 * headwind that drags you and the outward creep that walks you to the rim are the same statement
 * about the same ride. The wall <i>climb</i> was never part of that idea at all and has its own
 * {@code wall} section now, because it is the opposite force - the one that keeps you on.</p>
 *
 * <h2>Units</h2>
 *
 * <p>Sable reports velocities in m/s (blocks per second), and Minecraft's gravity of
 * 0.08 blocks/tick^2 is 32 m/s^2 in those units - that is {@link #GRAVITY}. "1 g" anywhere in this
 * mod means 32, not 9.81.</p>
 */
public final class CfConfig {

    /**
     * Minecraft's gravity in Sable's units: 0.08 blocks/tick^2 * 20^2 = 32 m/s^2.
     *
     * <p>Not 9.81. Mixing the two is the easiest way to get every threshold in here wrong by a
     * factor of three.</p>
     */
    public static final double GRAVITY = 32.0;

    // ---------------------------------------------------------------- drag shaping

    /** Air speed, m/s, at which drag reaches one gravity at {@code strength = 1}. */
    private static final double AIR_REFERENCE_SPEED = 40.0;

    /**
     * Exponent of the drag law. Real aerodynamic drag is quadratic; this is 1.35.
     *
     * <p>Quadratic drag does almost nothing until a knee and then removes all control at once,
     * which reads as "nothing, nothing, thrown off". At 1.35 the ramp is gradual enough that
     * sliding is a state you can steer against for a few seconds.</p>
     */
    private static final double AIR_EXPONENT = 1.35;

    /** Soft ceiling on drag, in g. Past this the curve compresses instead of continuing. */
    private static final double AIR_SOFT_CAP_G = 2.2;

    // ---------------------------------------------------------------- body orientation

    /**
     * Sharpness of the blend between contacting faces, for the surface normal the PHYSICS uses.
     * Lower is a rounder floor-to-wall ramp, higher approaches a hard switch.
     */
    public static final double SURFACE_BLEND_SHARPNESS = 6.0;

    /** Half-life, seconds, of the body orientation chasing its target. */
    public static final double BODY_HALF_LIFE = 0.16;

    /**
     * Cap on how fast the body may turn, deg/s.
     *
     * <p>The body drives the camera and nothing else, so this can be quick; what must not be quick
     * is the collision box. See {@link #HITBOX_SLEW_DEG_PER_S}.</p>
     */
    public static final double BODY_SLEW_DEG_PER_S = 240.0;

    /**
     * Half-life, seconds, of the COLLISION orientation chasing the body.
     *
     * <p>Deliberately slower than the body. Sable turns the collision box about the player's eye
     * rather than their feet, so every degree of lean sweeps the feet sideways by about
     * {@code 2 * 1.62 * sin(angle/2)} metres - a quarter turn moves them well over a metre. Move
     * that fast next to a wall and SAT reports a metre-deep penetration, which comes back as a
     * metre-long shove. Slow is not politeness here, it is the difference between leaning and
     * being fired through the wall.</p>
     */
    public static final double HITBOX_HALF_LIFE = 0.30;

    /** Cap on how fast the collision box may turn, deg/s. See {@link #HITBOX_HALF_LIFE}. */
    public static final double HITBOX_SLEW_DEG_PER_S = 70.0;

    /**
     * How far to pull the body box in on each axis before testing whether a lean fits, in blocks.
     *
     * <p>Without it a body resting on a floor is permanently "blocked", because it is touching the
     * floor - which is the normal state of affairs and not a reason to refuse to move.</p>
     */
    public static final double CLEARANCE_SHRINK = 0.06;

    /**
     * Half-life, seconds, of the low-pass on the frame acceleration.
     *
     * <p>Applied in the SUB-LEVEL's own frame, not in world space. A steady spin is a constant
     * vector in the deck's frame but a rotating one in the world, and a first-order lag on a
     * rotating vector does not merely smooth it, it rotates it backwards - about 16 degrees at
     * 2.5 rad/s. That phase error appears as a permanent tangential shove that friction then has
     * to fight, which is a slide out of nothing.</p>
     */
    public static final double FRAME_ACCEL_HALF_LIFE = 0.10;

    /** Frame acceleration below this, in g, is treated as exactly zero. Anti-noise only. */
    public static final double FRAME_ACCEL_DEADZONE_G = 0.06;

    /** Frame acceleration, in g, at which the dead-zone fade has finished. */
    public static final double FRAME_ACCEL_FULL_G = 0.14;

    /**
     * Rotation rate, rad/s, below which nothing rotation-specific happens: no wall attach, no
     * outward creep, no wall climb.
     *
     * <p>A gate on the angular velocity itself, not on a force a translation could also produce.
     * That is the structural answer to "the mod cannot tell whether the sub-level is rotating".</p>
     */
    public static final double SPIN_DEADZONE = 0.15;

    /** Rotation rate, rad/s, at which the rotation-specific features are fully faded in. */
    public static final double SPIN_FULL = 0.45;

    /** cos of the angle past which a contact face is REPORTED as a wall. Display only. */
    public static final double WALL_COSINE = 0.55;

    /** Half-life, seconds, of the low-pass on the player's measured velocity across the deck. */
    public static final double DECK_RELATIVE_HALF_LIFE = 0.08;

    /**
     * How far felt gravity must have left world gravity before the body leans at all.
     *
     * <p>Measured as {@code 1 - feltUp.y}, so zero means the ride is adding nothing vertical to
     * your sense of down and two means it has turned it upside down.</p>
     *
     * <p>This replaced gating the lean on the ride's <i>share of the press</i>, and the difference
     * is the whole reason wall entry never worked. The share is near zero on the FLOOR of a drum -
     * correctly, because a horizontal centrifugal vector presses you into a floor by nothing - so a
     * player walking towards the wall stayed bolt upright right up until the moment they needed not
     * to be. Felt gravity has no such blind spot: it is already leaning outward while you are still
     * on the floor, which is exactly when the body should start to.</p>
     *
     * <p>The guarantee that used to come from the share is not lost. A sub-level that is merely
     * travelling has felt gravity equal to world gravity by construction, so this is identically
     * zero for lifts, ships and drawbridges however hard they accelerate.</p>
     */
    public static final double TILT_DEVIATION_LOW = 0.04;

    /** Deviation of felt gravity at which the lean is fully available. */
    public static final double TILT_DEVIATION_HIGH = 0.35;

    /** How well the face you are touching must oppose felt-down before you stand on it. */
    public static final double TILT_ALIGN_LOW = 0.15;

    /** Alignment at which the face counts as a full floor for you. */
    public static final double TILT_ALIGN_HIGH = 0.70;

    /** Ride share at which climbing a surface you are pinned to starts to be possible. */
    public static final double CLIMB_SHARE_LOW = 0.30;

    /** Ride share at which climbing is fully available. */
    public static final double CLIMB_SHARE_HIGH = 0.65;

    /**
     * How fast the player may already be closing on a surface, m/s, before the mod stops pressing
     * them into it any harder.
     *
     * <p>Sable resolves contact positionally and does not zero {@code deltaMovement} against a
     * sub-level wall, so an into-surface acceleration applied every tick with nothing watching the
     * result accumulates into a stored velocity. It stays invisible until one tick resolves the
     * whole penetration at once and fires the player through the wall.</p>
     */
    public static final double PRESS_MAX_SPEED = 6.0;

    /** Acceleration, m/s^2, the walk keys get along a surface you are pinned to. */
    public static final double WALL_WALK_ACCEL = 26.0;

    /** Hard ceiling, m/s, on the velocity handed over by a release. A sanity rail, not a knob. */
    public static final double RELEASE_MAX_SPEED = 40.0;

    /**
     * Below this cosine, world up and the target up are treated as antiparallel.
     *
     * <p>A rotation from one vector to its exact opposite has no defined axis - every perpendicular
     * is a valid answer - and JOML picks one arbitrarily. Standing under the deck at the top of a
     * loop is exactly that case, and an arbitrary axis there makes the body spin about something
     * random.</p>
     */
    public static final double ANTIPARALLEL_COSINE = -0.995;

    public static final ModConfigSpec SPEC;

    // ---------------------------------------------------------------- rotating frame

    public static final ModConfigSpec.BooleanValue CENTRIFUGAL_ENABLED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue CORIOLIS_STRENGTH;
    public static final ModConfigSpec.DoubleValue MAX_ACCEL_G;

    // ---------------------------------------------------------------- air resistance

    public static final ModConfigSpec.BooleanValue AIR_ENABLED;
    public static final ModConfigSpec.DoubleValue AIR_STRENGTH;
    public static final ModConfigSpec.DoubleValue AIR_SLIDE;
    public static final ModConfigSpec.DoubleValue AIR_SLIDE_MAX_SPEED;

    // ---------------------------------------------------------------- grip and attach

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

    // ---------------------------------------------------------------- wall walking

    public static final ModConfigSpec.BooleanValue WALL_ENABLED;
    public static final ModConfigSpec.DoubleValue WALL_STRENGTH;
    public static final ModConfigSpec.DoubleValue WALL_PRESS_G;
    public static final ModConfigSpec.DoubleValue WALL_MAX_SPEED;

    // ---------------------------------------------------------------- release

    public static final ModConfigSpec.BooleanValue RELEASE_ENABLED;
    public static final ModConfigSpec.DoubleValue RELEASE_DECEL_G;
    public static final ModConfigSpec.DoubleValue RELEASE_MIN_SPEED;

    // ---------------------------------------------------------------- hitbox

    public static final ModConfigSpec.BooleanValue HITBOX_ENABLED;
    public static final ModConfigSpec.DoubleValue HITBOX_AMOUNT;
    public static final ModConfigSpec.DoubleValue HITBOX_MAX_DEG;

    // ---------------------------------------------------------------- camera

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ModConfigSpec.DoubleValue CAMERA_AMOUNT;
    public static final ModConfigSpec.DoubleValue CAMERA_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DAMPING;
    public static final ModConfigSpec.DoubleValue CAMERA_SMOOTHING;
    public static final ModConfigSpec.DoubleValue CAMERA_DEADBAND_DEG;
    public static final ModConfigSpec.DoubleValue CAMERA_PITCH_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DECK_LEAN;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAN;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAN_MAX_DEG;
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
                        "to match gravity, which is roughly what a real rotor ride actually spins at.",
                        "On the FLOOR of a drum the centrifugal vector is horizontal, so it presses you",
                        "into the floor by nothing and only pushes you outward. Sliding out to the wall",
                        "is the ride, not a bug. The press appears once you reach the wall.")
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
                        "what it would have done.")
                .defineInRange("coriolis_strength", 0.3, 0.0, 2.0);

        MAX_ACCEL_G = b
                .comment("Hard clamp on everything this mod adds, in g (1 g = 32 m/s^2).",
                        "A safety rail, not a tuning knob: a contraption that teleports produces one tick",
                        "of enormous bogus acceleration, and without this you get launched into orbit.")
                .defineInRange("max_accel_g", 8.0, 0.5, 64.0);

        b.pop();

        b.comment("Getting peeled off. This is the ONLY thing in the mod that slides you off a",
                        "contraption, which is the point - there used to be a second knob called 'slip'",
                        "doing half the same job, so switching air resistance off did not stop you",
                        "sliding. It is one idea and it lives here.",
                        "Two parts of the same statement about the ride:",
                        "  strength - the headwind. You are being carried through still air at omega x r,",
                        "             and that is what makes holding on a thing you DO. Measured against",
                        "             the air the sub-level carries, not the world: the deck's rigid",
                        "             translation is subtracted, so a platform cruising at 25 m/s is not a",
                        "             gale for someone standing on it. Rotation survives; translation",
                        "             cancels to zero identically.",
                        "  slide    - the outward creep. In a drum you should ease from the middle out to",
                        "             the rim and end up leaning on the lip. Friction alone will not do it:",
                        "             friction is symmetric, so once it holds you it holds you exactly",
                        "             where you are and the ride never moves you anywhere.",
                        "Set air_resistance to 0 and wall walking becomes effortless. That is intended:",
                        "this is the difficulty knob for it.")
                .push("air_resistance");

        AIR_ENABLED = b
                .comment("/sable_cf air_resistance enable|disable. Off stops BOTH the headwind and the",
                        "outward creep, so it really does mean 'nothing peels me off'.")
                .define("enabled", true);

        AIR_STRENGTH = b
                .comment("The one knob. /sable_cf air_resistance <value>",
                        "1.0 is the tuned default. Higher = flimsier player, peeled off a wall sooner.")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        AIR_SLIDE = b
                .comment("Fraction of the surface-tangential centrifugal load let past friction as",
                        "outward creep. 0.30 is a slow, resistible drift you can walk against.",
                        "0 removes the creep and leaves the headwind alone.")
                .defineInRange("slide", 0.30, 0.0, 2.0);

        AIR_SLIDE_MAX_SPEED = b
                .comment("Cap on the outward creep, m/s. It is a drift, not a launch: the push fades out",
                        "as you approach this, so it settles at a terminal speed instead of clipping.")
                .defineInRange("slide_max_speed", 2.2, 0.1, 20.0);

        b.pop();

        b.comment("Footing: how much sideways load your feet hold before you slide.",
                        "Raised hard from the old 1.15. At 1.15 any contraption that turned briskly beat",
                        "friction, and because the excess was applied every tick with nothing observing",
                        "the resulting slide, one turn started a slide that never stopped. Two fixes, and",
                        "they are separate: grip decides WHETHER you move, slide_cap_g and the speed limit",
                        "decide how far and how fast - so a sharp turn shoves you rather than evicting",
                        "you.")
                .push("grip");

        GRIP_ENABLED = b
                .comment("/sable_cf grip enable|disable. Off means no friction at all: everything slides.")
                .define("enabled", true);

        GRIP_STRENGTH = b
                .comment("The one knob: static friction coefficient. /sable_cf grip <value>",
                        "2.2 is well above a realistic boot-on-metal 0.85, on purpose. This is the number",
                        "that decides whether a deck yanking sideways takes you with it, and being",
                        "thrown off something you were standing on is the least fun outcome available.",
                        "Lower it for an ice-rink feel; raise it and almost nothing can move you.")
                .defineInRange("strength", 2.2, 0.0, 8.0);

        GRIP_BRACE_BONUS = b
                .comment("Friction multiplier while sneaking - the 'hold on' input, so resisting is a",
                        "choice you make rather than something that either always or never works.")
                .defineInRange("brace_bonus", 1.9, 1.0, 4.0);

        GRIP_MIN_PRESS_G = b
                .comment("Press, in g, below which a surface is not holding you and the body stays",
                        "upright. Bottom of the lean ramp.")
                .defineInRange("min_press_g", 0.2, 0.0, 4.0);

        GRIP_FULL_PRESS_G = b
                .comment("Press, in g, at which the surface is holding you completely. Top of the lean",
                        "ramp and bottom of the wall-climb ramp.",
                        "0.8 rather than 1.0: a homemade drum is not going to spin at rotor-ride speeds,",
                        "and an effect you cannot reach is not a feature.")
                .defineInRange("full_press_g", 0.8, 0.1, 16.0);

        GRIP_SLIDE_CAP_G = b
                .comment("Most acceleration a slide may ever have, in g, no matter how violent the ride.",
                        "The gameplay rail. Without it the excess past friction is unbounded, and a",
                        "contraption snapping sideways does not slide you - it deletes you from the deck.")
                .defineInRange("slide_cap_g", 1.1, 0.05, 16.0);

        ATTACH_PRESS_G = b
                .comment("Press, in g, at which touching a surface sideways latches you to it.")
                .defineInRange("attach_press_g", 0.45, 0.05, 16.0);

        ATTACH_RELEASE_G = b
                .comment("Press, in g, below which an attached player lets go again. Deliberately lower",
                        "than attach_press_g: that gap is hysteresis, and without it you flicker between",
                        "stuck and free every tick at the threshold. Jumping always releases.")
                .defineInRange("release_press_g", 0.25, 0.0, 16.0);

        ATTACH_SHARE = b
                .comment("Fraction of the press into a surface that must come from the RIDE rather than",
                        "from gravity, 0..1, before it can latch. This is the 'only in the right",
                        "scenarios' rule, and it is what makes the check safe on any surface orientation:",
                        "an ordinary floor is pressed by gravity so its share is near zero and it can",
                        "never latch, while the inside of a drum is near one whether it is beside you,",
                        "below you, or above you at the top of a loop.")
                .defineInRange("attach_share", 0.45, 0.0, 1.0);

        ATTACH_ADHESION_G = b
                .comment("Extra acceleration into the surface while attached, in g. Small on purpose.",
                        "Sable resolves contact in a limited number of substeps, so a body exactly",
                        "touching a moving wall drifts a hair off it and back every tick and 'stuck to",
                        "the drum' becomes a coin flip. This keeps the contact closed. It is not what",
                        "holds you up - that is friction.")
                .defineInRange("adhesion_g", 0.35, 0.0, 4.0);

        b.pop();

        b.comment("Wall walking. The mod is fundamentally about this.",
                        "Two things have to be true before a wall behaves like a floor, and only one of",
                        "them used to be here:",
                        "  1. the along-surface pull of gravity is cancelled, in proportion to how hard",
                        "     the RIDE is pressing you in - a rider in a real rotor walks up the drum",
                        "     because press gives their boots the friction to beat gravity;",
                        "  2. the walk keys actually drive you along the surface. Without this the first",
                        "     one only buys you the right to hover: you press W, Minecraft pushes you",
                        "     horizontally into the wall, and nothing happens. This is what turns",
                        "     'gravity is off' into walking.",
                        "Rotation-gated and share-gated, so an ordinary wall in an ordinary building is",
                        "still a wall.")
                .push("wall");

        WALL_ENABLED = b
                .comment("/sable_cf wall enable|disable")
                .define("enabled", true);

        WALL_STRENGTH = b
                .comment("The one knob. /sable_cf wall <value>",
                        "Scales both the gravity cancellation and the walk drive together, because they",
                        "are one mechanic. Above 1 you can climb on less press than is really justified.")
                .defineInRange("strength", 1.0, 0.0, 2.0);

        WALL_PRESS_G = b
                .comment("Centrifugal press, in g, at which the surface handles exactly like a floor.",
                        "Ramps from grip.full_press_g to here, and the WIDTH is the point: press on a real",
                        "drum wanders by a few m/s^2 tick to tick, so a narrow band would be a switch with",
                        "noise on it and the assist would snap on and off several times a second.")
                .defineInRange("press_g", 1.3, 0.2, 16.0);

        WALL_MAX_SPEED = b
                .comment("How fast the assist may carry you along the surface, m/s. Near walking pace so",
                        "it reads as climbing rather than as being fired out of the ride.")
                .defineInRange("max_speed", 3.4, 0.1, 12.0);

        b.pop();

        b.comment("Release: what happens when the contraption stops but you do not.",
                        "A lift rising with you on it jams its edge against a frame. The sub-level stops",
                        "in one tick; you were doing 8 m/s upward a moment ago, and the honest outcome is",
                        "that you keep going - straight up, out through the middle.",
                        "Only ever applies to a player who was ALREADY attached.")
                .push("release");

        RELEASE_ENABLED = b
                .comment("/sable_cf release enable|disable")
                .define("enabled", true);

        RELEASE_DECEL_G = b
                .comment("How hard the deck has to stop, in g, to throw you clear. High on purpose:",
                        "this must fire for a genuine hard stop and never for normal manoeuvring.")
                .defineInRange("decel_g", 9.0, 1.0, 64.0);

        RELEASE_MIN_SPEED = b
                .comment("How fast the deck had to be carrying you, m/s, for a stop to count.")
                .defineInRange("min_speed", 3.5, 0.5, 40.0);

        b.pop();

        b.comment("Rotating the player's collision box with the surface.",
                        "Sable already snaps the box's YAW to the sub-level's grid by itself, which is the",
                        "Sure Footing behaviour - square to the contraption, not to the world, so doorways",
                        "line up. This section is only about the LEAN on top of that.",
                        "Read max_deg before changing anything here. Sable turns the box about the",
                        "player's EYE, not their feet, so a lean of A degrees sweeps the feet sideways by",
                        "2 * 1.62 * sin(A/2) blocks - 0.28 at 10 degrees, 0.97 at 35, 2.3 at 90. Next to a",
                        "wall that displacement is a penetration, SAT returns it as a minimum translation",
                        "vector, and Sable's near-vertical branch redirects the whole length along your",
                        "body up. That is precisely the 'I tried to get on the wall and it spat me",
                        "through it'. The lean is capped, slewed slowly and clearance-tested against real",
                        "voxels before it is handed over, so it can never rotate into geometry.",
                        "You do not need a big lean to stand on a wall. An upright box pressed against a",
                        "wall occupies free space; what makes it feel like standing is the camera and the",
                        "wall section, not the box.")
                .push("hitbox");

        HITBOX_ENABLED = b
                .comment("/sable_cf hitbox enable|disable. Off leaves Sable's grid yaw alone - you keep",
                        "the Sure Footing behaviour and lose only the lean.")
                .define("enabled", true);

        HITBOX_AMOUNT = b
                .comment("How much of the body lean the collision box follows, 0..1, before the cap.",
                        "/sable_cf hitbox <value>")
                .defineInRange("amount", 1.0, 0.0, 1.0);

        HITBOX_MAX_DEG = b
                .comment("Hard cap on the collision lean, degrees. THE safety number in this mod.",
                        "25 keeps the worst-case foot sweep near 0.7 blocks, which the clearance test can",
                        "still refuse cleanly. Raise it and you are trading 'my body looks right' against",
                        "'I get flung when I lean near geometry' - the eye pivot is upstream ask #2 in",
                        "docs/UPSTREAM.md and this cap disappears the day it becomes a feet pivot.")
                .defineInRange("max_deg", 25.0, 0.0, 180.0);

        b.pop();

        b.comment("Camera. Requires Aeronautics Camera Sync with addTiltSource() (api-beta, > 1.3.7).",
                        "Aimed at the BODY's orientation, plus a small CAPPED lean towards felt gravity.",
                        "The cap is the fix for 'it heels over violently on turns'. Leaning towards felt",
                        "gravity is right, but the angle it asks for is unbounded - a brisk turn produces",
                        "several g sideways and asks for 60 or 70 degrees of roll, which is nobody's idea",
                        "of a bank. lean_max_deg turns that into a fixed, small, readable tilt while",
                        "leaving the RESPONSE - how quickly it gets there - untouched, which is the part",
                        "that actually reads as reactive.")
                .push("camera");

        CAMERA_ENABLED = b
                .comment("/sable_cf camera enable|disable")
                .define("enabled", true);

        CAMERA_AMOUNT = b
                .comment("Overall tilt amount. The one knob. /sable_cf camera <value>",
                        "0 keeps the camera level while everything else still works.")
                .defineInRange("amount", 1.0, 0.0, 2.0);

        CAMERA_LEAN = b
                .comment("Fraction of the angle to felt gravity the view leans, on top of following the",
                        "body, 0..1. Then capped by lean_max_deg. This is the term that makes a change of",
                        "direction felt at all when you are NOT pinned, so it should not be zero.")
                .defineInRange("lean", 0.35, 0.0, 1.0);

        CAMERA_LEAN_MAX_DEG = b
                .comment("Hard cap on that lean, degrees. Raise this if turns feel numb; lower it if they",
                        "feel sickening. It does NOT limit standing on a wall - that comes from the body",
                        "and is bounded by max_tilt_deg instead.")
                .defineInRange("lean_max_deg", 12.0, 0.0, 90.0);

        CAMERA_RESPONSE = b
                .comment("Base spring frequency, rad/s. 4.5 settles in about 0.45 s - soft, not sluggish.",
                        "A change of direction raises it on its own; see jolt_gain.")
                .defineInRange("response", 4.5, 1.0, 40.0);

        CAMERA_DAMPING = b
                .comment("Damping ratio. 1.05 is a shade past critical: the fastest approach that cannot",
                        "overshoot, with a little margin so a spike cannot make it ring.")
                .defineInRange("damping", 1.05, 0.4, 2.0);

        CAMERA_SMOOTHING = b
                .comment("Half-life, seconds, of the low-pass on the camera's TARGET (not on the camera).",
                        "Filtering the target rather than the output is what makes this gentle without",
                        "making it late: the spring still chases hard, it just is not handed a jittering",
                        "goal to chase.")
                .defineInRange("smoothing", 0.14, 0.0, 1.0);

        CAMERA_DEADBAND_DEG = b
                .comment("Jitter dead zone, degrees. Target motion smaller than this does not move the",
                        "camera at all - the fix for micro-tremble, since the pose arrives over the",
                        "network and is never perfectly still. Applied as slop, not as a step.")
                .defineInRange("deadband_deg", 0.7, 0.0, 10.0);

        CAMERA_JOLT_GAIN = b
                .comment("How much a CHANGE OF DIRECTION speeds the camera up. Speeds it UP - it does not",
                        "lean it further. That separation is deliberate: 'reactive' is a question about",
                        "time, and answering it with amplitude is what made turns nauseating.",
                        "Driven by how fast the target is swinging plus a little angular acceleration, so",
                        "it is large when the ride changes what it is doing and zero when it is doing the",
                        "same thing quickly. The curve is x/(1+x): monotonic, bounded, cannot spike.")
                .defineInRange("jolt_gain", 0.9, 0.0, 6.0);

        CAMERA_LOOP_SUPPRESSION = b
                .comment("How much to stop following the target once the sub-level is going all the way",
                        "round, 0..1. In a loop the body's up sweeps a full circle, and a camera that",
                        "tracks it honestly rolls 360 degrees - the single most nauseating thing a camera",
                        "can do, and not what a human does either: you keep your head with your body and",
                        "let the world go round you.")
                .defineInRange("loop_suppression", 0.85, 0.0, 1.0);

        CAMERA_WALK_DAMPING = b
                .comment("How much to calm the camera while you are moving across the deck, 0..1.",
                        "Walking on a spinner changes your radius every tick, so the target moves even",
                        "though the ride is doing nothing new. Only ever damps.")
                .defineInRange("walk_damping", 0.6, 0.0, 1.0);

        CAMERA_PITCH_RESPONSE = b
                .comment("How much of the pitch component to apply, 0..1. Roll is well tolerated by the",
                        "human vestibular system; pitch is what makes people queasy. Yaw is dropped.")
                .defineInRange("pitch_response", 0.7, 0.0, 1.0);

        CAMERA_DECK_LEAN = b
                .comment("Small blend from the target towards the geometric surface normal, 0..1 - the",
                        "proprioceptive hint that tells you which way the floor is. Keep it small: at 1.0",
                        "it reproduces the deck-locked 'glued to the plane' feel it exists to avoid.")
                .defineInRange("deck_lean", 0.08, 0.0, 1.0);

        CAMERA_MAX_TILT_DEG = b
                .comment("Hard cap on total tilt, degrees. 95 so that being genuinely pinned to a drum",
                        "wall - or under the deck at the top of a loop - can actually be shown.",
                        "It stays short of 180, where a rotation vector has no defined axis.")
                .defineInRange("max_tilt_deg", 95.0, 0.0, 150.0);

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
                .comment("Blocks drawn per m/s^2. 0.025 makes 1 g exactly 0.8 blocks long.")
                .defineInRange("accel_scale", 0.025, 0.001, 1.0);

        DEBUG_VELOCITY_SCALE = b
                .comment("Blocks drawn per m/s.")
                .defineInRange("velocity_scale", 0.08, 0.001, 2.0);

        DEBUG_MIN_LENGTH = b
                .comment("Arrows shorter than this are drawn at this length instead, so a small but real",
                        "force is still readable as a direction. Below the dead zone nothing is drawn.")
                .defineInRange("min_length", 0.28, 0.0, 4.0);

        DEBUG_MAX_LENGTH = b
                .comment("Arrows are never drawn longer than this.")
                .defineInRange("max_length", 3.5, 0.2, 32.0);

        DEBUG_ALPHA = b
                .comment("Arrow opacity, 0..1. Translucent on purpose.")
                .defineInRange("alpha", 0.7, 0.05, 1.0);

        DEBUG_SMOOTHING_MS = b
                .comment("Half-life of the arrow smoothing in milliseconds.")
                .defineInRange("smoothing_ms", 70, 0, 1000);

        b.pop();

        SPEC = b.build();
    }

    /**
     * Drag acceleration magnitude, m/s^2, for a speed through the air in m/s.
     *
     * <p>Superlinear but not quadratic, and soft capped.</p>
     */
    public static double dragMagnitude(final double airSpeed) {
        if (!(airSpeed > 0.0) || !Double.isFinite(airSpeed)) {
            return 0.0;
        }

        final double strength = AIR_STRENGTH.