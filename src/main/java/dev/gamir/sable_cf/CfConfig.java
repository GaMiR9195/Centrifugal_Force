package dev.gamir.sable_cf;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config, and the only place a number lives. Commands write straight into these and save.
 *
 * <h2>The model, in one paragraph</h2>
 *
 * <p>This mod is wall walking with a physical trigger. A sub-level that <b>rotates</b> defines a
 * gravity plane - one of its six local faces - and while the ride holds you against that face you
 * stand on it: body, collision box and camera. How firmly it holds you is one number, {@code stick}.
 * The only thing that stops you being glued on for free is {@code air_resistance}, which adds a load
 * friction has to fight. Everything else in this file is either the shape of {@code stick} or the
 * shape of the friction solve.</p>
 *
 * <h2>Units</h2>
 *
 * <p>Sable reports m/s. Minecraft gravity is 0.08 blocks/tick^2 = 32 m/s^2, which is
 * {@link #GRAVITY}. "1 g" here means 32, not 9.81.</p>
 */
public final class CfConfig {

    /** Minecraft gravity in Sable's units. Not 9.81. */
    public static final double GRAVITY = 32.0;

    /** One tick, in seconds. Every half-life in this file is converted against it. */
    public static final double TICK = 1.0 / 20.0;

    // ---------------------------------------------------------------- drag shaping

    private static final double AIR_REFERENCE_SPEED = 40.0;

    /** Superlinear, not quadratic. Quadratic drag reads as "nothing, nothing, thrown off". */
    private static final double AIR_EXPONENT = 1.35;

    private static final double AIR_SOFT_CAP_G = 2.2;

    // ---------------------------------------------------------------- frame kinematics

    /**
     * Half-life, s, of the low-pass on the sub-level's ANGULAR velocity.
     *
     * <p>Angular velocity is what gets filtered now, and that is the whole reason the force stopped
     * lagging. The old code filtered the acceleration itself, which on a steady spin is a vector
     * rotating at omega - and a first-order lag on a rotating vector turns it sideways. Omega is
     * <i>constant</i> on a steady spin, in world space as well as in the deck's, so filtering it
     * costs no direction error at all. The centripetal term is then rebuilt from the CURRENT
     * position: {@code omega x (omega x r)} points exactly at the axis, by construction.</p>
     */
    public static final double OMEGA_HALF_LIFE = 0.09;

    /** Half-life, s, of the low-pass on angular acceleration. Longer: it is a second difference. */
    public static final double ALPHA_HALF_LIFE = 0.16;

    /** Half-life, s, of the low-pass on the pivot's linear acceleration. */
    public static final double PIVOT_ACCEL_HALF_LIFE = 0.14;

    /**
     * Linear acceleration of the whole contraption below this, in g, is exactly zero.
     *
     * <p>Deliberately much harsher than the old global dead-zone, and it can afford to be: a lift
     * or a ship produces ONLY this term, so throwing it away entirely is the structural guarantee
     * that a non-rotating sub-level does nothing. The rotational terms are not dead-zoned at all -
     * they are gated on {@link #SPIN_DEADZONE} instead, which is a property of the ride rather than
     * a magnitude that noise can reach.</p>
     */
    public static final double LINEAR_ACCEL_DEADZONE_G = 0.35;

    public static final double LINEAR_ACCEL_FULL_G = 0.75;

    /**
     * Spin rate, rad/s, below which the sub-level is not "a ride": no plane, no stick, no climb.
     * A gate on angular velocity itself, not on a force a translation could also produce.
     */
    public static final double SPIN_DEADZONE = 0.12;

    public static final double SPIN_FULL = 0.35;

    /** Faster than this across the deck is a teleport or a re-anchor, not a slide. */
    public static final double MAX_DECK_RELATIVE = 100.0;

    public static final double DECK_RELATIVE_HALF_LIFE = 0.07;

    // ---------------------------------------------------------------- plane switching

    /** Contacts are re-probed this far outside the body, in local blocks. */
    public static final double PROBE_DEPTH = 0.14;

    /** How much to pull a probe slab in on its other two axes, so corners do not read as faces. */
    public static final double PROBE_SIDE_SHRINK = 0.08;

    /** How far to shrink the body box before testing whether a lean fits, blocks. */
    public static final double CLEARANCE_SHRINK = 0.05;

    /** Below this cosine, world up and the target are antiparallel and the axis must be supplied. */
    public static final double ANTIPARALLEL_COSINE = -0.995;

    /** cos of the angle past which a contact is REPORTED as a wall. Display only. */
    public static final double WALL_COSINE = 0.55;

    // ---------------------------------------------------------------- misc rails

    /**
     * How fast the player may already be closing on a surface, m/s, before the mod stops pressing
     * them into it harder. Sable resolves contact positionally and never zeroes deltaMovement
     * against a sub-level face, so an unwatched into-surface term accumulates and then fires you
     * through the wall in a single tick.
     */
    public static final double PRESS_MAX_SPEED = 6.0;

    /** Acceleration, m/s^2, the walk keys get along a surface you are pinned to. */
    public static final double WALL_WALK_ACCEL = 30.0;

    /** Hard ceiling, m/s, on a release velocity. A sanity rail. */
    public static final double RELEASE_MAX_SPEED = 40.0;

    /** Above this press, in g, fall damage is suppressed: you are being held, not falling. */
    public static final double FALL_RESET_G = 1.2;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue CENTRIFUGAL_ENABLED;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue CENTRIFUGAL_LEAD;
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
    public static final ModConfigSpec.DoubleValue GRIP_SLIDE_DAMPING;
    public static final ModConfigSpec.DoubleValue GRIP_SLIDE_CAP_G;

    public static final ModConfigSpec.BooleanValue WALL_ENABLED;
    public static final ModConfigSpec.DoubleValue WALL_STRENGTH;
    public static final ModConfigSpec.DoubleValue WALL_MIN_PRESS_G;
    public static final ModConfigSpec.DoubleValue WALL_FULL_PRESS_G;
    public static final ModConfigSpec.DoubleValue WALL_LOOP_ASSIST;
    public static final ModConfigSpec.DoubleValue WALL_MAX_SPEED;

    public static final ModConfigSpec.BooleanValue PLANE_ENABLED;
    public static final ModConfigSpec.DoubleValue PLANE_SWITCH_MARGIN_G;
    public static final ModConfigSpec.IntValue PLANE_DWELL_TICKS;
    public static final ModConfigSpec.DoubleValue PLANE_HALF_LIFE;
    public static final ModConfigSpec.DoubleValue PLANE_SLEW_DEG_PER_S;

    public static final ModConfigSpec.BooleanValue RELEASE_ENABLED;
    public static final ModConfigSpec.DoubleValue RELEASE_DECEL_G;
    public static final ModConfigSpec.DoubleValue RELEASE_MIN_SPEED;

    public static final ModConfigSpec.BooleanValue HITBOX_ENABLED;
    public static final ModConfigSpec.DoubleValue HITBOX_AMOUNT;
    public static final ModConfigSpec.DoubleValue HITBOX_MAX_DEG;
    public static final ModConfigSpec.BooleanValue HITBOX_CENTRE_PIVOT;
    public static final ModConfigSpec.DoubleValue HITBOX_HALF_LIFE;
    public static final ModConfigSpec.DoubleValue HITBOX_SLEW_DEG_PER_S;

    public static final ModConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ModConfigSpec.DoubleValue CAMERA_AMOUNT;
    public static final ModConfigSpec.DoubleValue CAMERA_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_DAMPING;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAD;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAN;
    public static final ModConfigSpec.DoubleValue CAMERA_LEAN_MAX_DEG;
    public static final ModConfigSpec.DoubleValue CAMERA_PITCH_RESPONSE;
    public static final ModConfigSpec.DoubleValue CAMERA_MAX_TILT_DEG;
    public static final ModConfigSpec.DoubleValue CAMERA_SLEW_DEG_PER_S;
    public static final ModConfigSpec.DoubleValue CAMERA_LOOP_SUPPRESSION;

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
                        "The acceleration is built ANALYTICALLY from the sub-level's angular velocity",
                        "and your radius - a_pivot + alpha x r + omega x (omega x r) - rather than by",
                        "differencing the pose twice. One differentiation instead of two: the direction",
                        "is exact by construction (it points at the axis), and the second-derivative",
                        "noise that used to need a heavy filter simply is not produced.")
                .push("centrifugal_force");

        CENTRIFUGAL_ENABLED = b.comment("/sable_cf centrifugal_force enable|disable")
                .define("enabled", true);

        CENTRIFUGAL_STRENGTH = b
                .comment("The one knob. 1.0 is the physical value. /sable_cf centrifugal_force <value>")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        CENTRIFUGAL_LEAD = b
                .comment("Phase lead on the measured angular velocity, in ticks.",
                        "A backward difference of the pose is centred half a tick in the past, and",
                        "Sable's own velocity field is another half behind that. Extrapolating omega",
                        "forward by this many ticks removes the residual, which is what stopped the",
                        "debug arrows pointing a few degrees off to the side. 0 disables it; above ~1.5",
                        "it starts amplifying network jitter instead of cancelling lag.")
                .defineInRange("lead_ticks", 1.0, 0.0, 3.0);

        CORIOLIS_STRENGTH = b
                .comment("Scale on -2 omega x v, the term driven by your own walking. Full strength is",
                        "correct but reads as a mysterious sideways pull, and Sure Footing already",
                        "rotates your velocity with the frame, which cancels most of it.")
                .defineInRange("coriolis_strength", 0.25, 0.0, 2.0);

        MAX_ACCEL_G = b
                .comment("Hard clamp on everything this mod adds, in g. A safety rail, not a knob: a",
                        "contraption that teleports makes one tick of enormous bogus acceleration.")
                .defineInRange("max_accel_g", 8.0, 0.5, 64.0);

        b.pop();

        b.comment("Getting peeled off. The ONLY thing in the mod that takes you off a contraption.",
                        "Everything else holds you on, so this is the difficulty knob and the reason",
                        "wall walking is not free:",
                        "  strength - the headwind. Pinned in a drum you are carried through still air",
                        "             at omega x r, and that is what makes holding on a thing you DO.",
                        "             Measured against the air the sub-level carries: the rigid",
                        "             translation is subtracted, so a deck cruising at 25 m/s is not a",
                        "             gale for someone standing on it. Rotation survives, translation",
                        "             cancels identically.",
                        "  slide    - a shear the ride adds on top, as a fraction of the surface-",
                        "             tangential centrifugal load. Friction is symmetric, so without it",
                        "             the ride never walks you out to the rim.",
                        "Set both to 0 and wall walking becomes effortless: you stick and stay stuck.")
                .push("air_resistance");

        AIR_ENABLED = b
                .comment("/sable_cf air_resistance enable|disable. Off stops BOTH the headwind and the",
                        "shear, so it really does mean 'nothing peels me off'.")
                .define("enabled", true);

        AIR_STRENGTH = b.comment("The one knob. /sable_cf air_resistance <value>")
                .defineInRange("strength", 1.0, 0.0, 4.0);

        AIR_SLIDE = b
                .comment("Fraction of the surface-tangential centrifugal load added as outward shear.",
                        "0.25 is a drift you can walk against. 0 removes it.")
                .defineInRange("slide", 0.25, 0.0, 2.0);

        AIR_SLIDE_MAX_SPEED = b
                .comment("Reference speed, m/s, for the viscous term that gives a slide its terminal",
                        "drift. This is NOT a clip: the drag rises with speed and the slide settles",
                        "where push equals drag, which is why it now feels like sliding rather than",
                        "like hitting an invisible rail.")
                .defineInRange("slide_max_speed", 2.6, 0.1, 20.0);

        b.pop();

        b.comment("Footing. ONE Coulomb solve, and gravity is inside it.",
                        "That is the fix for sliding feeling arbitrary. The old solve fed friction only",
                        "the fictitious terms, so the pull of gravity along a wall was never something",
                        "your feet resisted - it was applied by vanilla behind the model's back, and",
                        "the only way to stop being dragged was to raise grip until the gate never",
                        "opened at all. Now the tangential load is (1 - stick) * gravity + ride + drag",
                        "+ coriolis, friction holds up to grip * press of it, the held part is CANCELLED",
                        "(that is what standing means), and only the excess moves you - against a",
                        "viscous term, so a slide has a terminal speed instead of an accelerating one.")
                .push("grip");

        GRIP_ENABLED = b.comment("/sable_cf grip enable|disable. Off means everything slides.")
                .define("enabled", true);

        GRIP_STRENGTH = b
                .comment("The one knob: static friction coefficient. /sable_cf grip <value>",
                        "1.2 rather than the old 2.2. The old value was propping up a broken solve -",
                        "it had to be absurd because the gate was the only thing standing between you",
                        "and an unbounded push. With gravity inside the solve and a terminal speed on",
                        "the outside, a boot-like coefficient behaves the way a boot does.")
                .defineInRange("strength", 1.2, 0.0, 8.0);

        GRIP_BRACE_BONUS = b
                .comment("Friction multiplier while sneaking - the 'hold on' input.")
                .defineInRange("brace_bonus", 1.8, 1.0, 4.0);

        GRIP_MIN_PRESS_G = b
                .comment("Press, in g, below which a surface is not holding you at all.")
                .defineInRange("min_press_g", 0.12, 0.0, 4.0);

        GRIP_FULL_PRESS_G = b
                .comment("Press, in g, at which a surface holds you completely.")
                .defineInRange("full_press_g", 0.55, 0.05, 16.0);

        GRIP_SLIDE_DAMPING = b
                .comment("Viscous damping on an active slide, 1/s. THE number that decides how a slide",
                        "feels. Terminal speed is roughly excess / damping, so 1.6 turns a 1 g excess",
                        "into about 20 m/s of eventual drift and a realistic 0.2 g excess into 4 m/s.",
                        "Raise it for a syrupy, controllable creep; lower it for ice.")
                .defineInRange("slide_damping", 1.6, 0.05, 20.0);

        GRIP_SLIDE_CAP_G = b
                .comment("Most acceleration a slide may ever have, in g. A rail for the pathological",
                        "case, not a tuning knob: a contraption snapping sideways should shove you, not",
                        "delete you from the deck.")
                .defineInRange("slide_cap_g", 1.4, 0.05, 16.0);

        b.pop();

        b.comment("Wall walking. The mod is fundamentally about this, and it is one number: stick.",
                        "stick is 0..1, how much of the apparent load the ride is holding for you:",
                        "  * the along-surface part is cancelled, so you can walk up a drum;",
                        "  * the pulling-off part is cancelled, so you can hang under the deck at the",
                        "    top of a loop.",
                        "The remaining (1 - stick) goes through friction like anything else. stick comes",
                        "from the RIDE's own press, and - separately - from the mere fact of being on a",
                        "rotating deck (loop_assist), which is what lets a slow 360 carry you round when",
                        "the physical press alone would not. Rotation-gated, so an ordinary wall on an",
                        "ordinary contraption is still an ordinary wall.")
                .push("wall");

        WALL_ENABLED = b.comment("/sable_cf wall enable|disable").define("enabled", true);

        WALL_STRENGTH = b
                .comment("The one knob. /sable_cf wall <value>. Scales stick as a whole.")
                .defineInRange("strength", 1.0, 0.0, 2.0);

        WALL_MIN_PRESS_G = b
                .comment("Centrifugal press, in g, at which the ride starts holding you.")
                .defineInRange("min_press_g", 0.20, 0.0, 16.0);

        WALL_FULL_PRESS_G = b
                .comment("Centrifugal press, in g, at which the surface handles exactly like a floor.",
                        "Ramps from min_press_g, and the WIDTH is the point: press on a real drum",
                        "wanders by a few m/s^2 tick to tick, so a narrow band is a switch with noise",
                        "on it and the assist snaps on and off several times a second.")
                .defineInRange("full_press_g", 0.90, 0.1, 16.0);

        WALL_LOOP_ASSIST = b
                .comment("How much stick a rotating deck grants on contact alone, 0..1, before any",
                        "press is measured. This is the gameplay grant that makes 'solnyshko' work: a",
                        "gentle 360 does not generate a gravity's worth of centrifugal force anywhere",
                        "in the loop, so on physics alone you fall off at the top. At 0.85 the ride",
                        "holds 85% of the load and air resistance walks you slowly down the deck - you",
                        "drift, but you complete the loop. Set 0 for a purely physical ride.")
                .defineInRange("loop_assist", 0.85, 0.0, 1.0);

        WALL_MAX_SPEED = b
                .comment("How fast the walk assist may carry you along the surface, m/s.")
                .defineInRange("max_speed", 4.0, 0.1, 12.0);

        b.pop();

        b.comment("Which way is down. The gravity plane is picked DISCRETELY from the sub-level's six",
                        "local faces and committed with hysteresis, instead of being blended.",
                        "Blending was the cause of two separate bugs and neither was tuning:",
                        "  * in the corner where floor meets wall the blended normal is diagonal, so",
                        "    projecting gravity onto it produced a large UPWARD vector - being lifted",
                        "    and shaken on the way onto a wall;",
                        "  * a blended normal points where no face of the contraption points, so the",
                        "    body could never be square to the grid the way Sure Footing keeps it.",
                        "A committed plane is one of exactly six directions, so on a level deck the",
                        "rotation is identity - not 'small', identity - and on a 360 ride it simply",
                        "travels with the deck all the way round.")
                .push("plane");

        PLANE_ENABLED = b
                .comment("Off keeps the body upright and leaves only the forces. Debug aid.")
                .define("enabled", true);

        PLANE_SWITCH_MARGIN_G = b
                .comment("How much more support, in g, a rival face needs before it takes over.",
                        "This is the hysteresis, and it is why the handover is crisp rather than",
                        "flickering: the face you are on keeps the plane until another one is clearly",
                        "better, then the switch happens once.")
                .defineInRange("switch_margin_g", 0.22, 0.0, 4.0);

        PLANE_DWELL_TICKS = b
                .comment("How many consecutive ticks a rival must stay ahead before it is committed.",
                        "Margin alone can still be beaten by a single noisy tick; a dwell cannot.")
                .defineInRange("dwell_ticks", 3, 0, 40);

        PLANE_HALF_LIFE = b
                .comment("Half-life, s, of the body rotating onto a newly committed plane.")
                .defineInRange("half_life", 0.13, 0.01, 2.0);

        PLANE_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the body may turn, deg/s.")
                .defineInRange("slew_deg_per_s", 400.0, 30.0, 2880.0);

        b.pop();

        b.comment("Release: the contraption stops but you do not. A lift rising with you on it jams",
                        "against a frame; you were doing 8 m/s upward, so you keep going out through the",
                        "middle. Only ever applies to a player the ride was ALREADY holding.")
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
                        "Sable already snaps the box YAW to the sub-level grid by itself - the Sure",
                        "Footing behaviour, square to the contraption rather than to the world. This",
                        "section is only the LEAN on top of it.",
                        "Read centre_pivot before touching max_deg. Stock Sable turns the box about the",
                        "player's EYE, and that is what used to fire people through walls: a lean of A",
                        "degrees slides the box sideways by 2 * 0.72 * sin(A/2) blocks, SAT returns that",
                        "displacement as a minimum translation vector, and Sable's near-vertical branch",
                        "redirects the whole length along your body up. centre_pivot moves the pivot to",
                        "the body's own centre, where a rotation displaces nothing at all - which is",
                        "also why a 360 ride can now rotate the box the whole way round safely: nothing",
                        "moves relative to the deck, so there is nothing to resolve.")
                .push("hitbox");

        HITBOX_ENABLED = b
                .comment("/sable_cf hitbox enable|disable. Off keeps Sable's grid yaw and loses only",
                        "the lean.")
                .define("enabled", true);

        HITBOX_AMOUNT = b
                .comment("How much of the body lean the box follows, 0..1. /sable_cf hitbox <value>")
                .defineInRange("amount", 1.0, 0.0, 1.0);

        HITBOX_CENTRE_PIVOT = b
                .comment("Rotate the collision box about the body's centre instead of the eye.",
                        "Implemented by cancelling one Sable method; if that mixin ever fails to apply",
                        "the box quietly goes back to the eye pivot and max_deg is what saves you, so",
                        "the cap is still enforced.")
                .define("centre_pivot", true);

        HITBOX_MAX_DEG = b
                .comment("Hard cap on the collision lean, degrees. 180 = no cap; the clearance test",
                        "against real voxels is the real limiter and it can refuse a posture outright.",
                        "Lower this to about 25 if you turn centre_pivot off.")
                .defineInRange("max_deg", 180.0, 0.0, 180.0);

        HITBOX_HALF_LIFE = b
                .comment("Half-life, s, of the collision box chasing the body. Slightly slower than",
                        "the body so the view leads and the box follows, which reads as weight.")
                .defineInRange("half_life", 0.18, 0.01, 2.0);

        HITBOX_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the collision box may turn, deg/s.")
                .defineInRange("slew_deg_per_s", 260.0, 10.0, 2880.0);

        b.pop();

        b.comment("Camera. Requires Aeronautics Camera Sync with addTiltSource().",
                        "The target is the COMMITTED gravity plane, scaled by stick - a discrete",
                        "direction times a smooth scalar. That matters more than any of the numbers",
                        "below: the old target was a blend of a filtered force, a felt-gravity lean and",
                        "a surface normal, so it jittered at source and every setting here was really a",
                        "way of hiding that. It cannot jitter now, so the spring is allowed to be fast,",
                        "and the dead band that used to be needed to kill the tremble is gone.")
                .push("camera");

        CAMERA_ENABLED = b.comment("/sable_cf camera enable|disable").define("enabled", true);

        CAMERA_AMOUNT = b
                .comment("Overall tilt amount. The one knob. /sable_cf camera <value>. 0 keeps the view",
                        "level while everything else still works.")
                .defineInRange("amount", 1.0, 0.0, 2.0);

        CAMERA_RESPONSE = b
                .comment("Spring frequency, rad/s. 9.0 settles in about a quarter of a second - brisk.",
                        "Safe to be this high only because the target no longer carries noise.")
                .defineInRange("response", 9.0, 1.0, 40.0);

        CAMERA_DAMPING = b
                .comment("Damping ratio. 1.0 is critical: the fastest approach that cannot overshoot,",
                        "which is the mathematical answer to 'snappy and smooth at the same time'.",
                        "Below 1 it will visibly bounce.")
                .defineInRange("damping", 1.0, 0.4, 2.0);

        CAMERA_LEAD = b
                .comment("How far ahead the camera aims, in seconds of the deck's own rotation, 0..0.25.",
                        "A spring always trails its target; leading the target by omega * lead cancels",
                        "most of that instead of stiffening the spring, so the view meets a plane change",
                        "rather than chasing it. This is the setting that makes it feel eager rather",
                        "than merely fast.")
                .defineInRange("lead", 0.07, 0.0, 0.25);

        CAMERA_LEAN = b
                .comment("Fraction of the angle to felt gravity the view leans on top of the plane,",
                        "0..1, then capped by lean_max_deg. This is what makes a change of direction",
                        "felt when you are NOT pinned, so it should not be zero.")
                .defineInRange("lean", 0.30, 0.0, 1.0);

        CAMERA_LEAN_MAX_DEG = b
                .comment("Hard cap on that lean, degrees. Raise if turns feel numb, lower if they feel",
                        "sickening. It does NOT limit standing on a wall - that comes from the plane",
                        "and is bounded by max_tilt_deg instead.")
                .defineInRange("lean_max_deg", 10.0, 0.0, 90.0);

        CAMERA_PITCH_RESPONSE = b
                .comment("How much of the pitch component to apply, 0..1. Roll is well tolerated; pitch",
                        "is what makes people queasy. Yaw is dropped entirely.")
                .defineInRange("pitch_response", 0.8, 0.0, 1.0);

        CAMERA_MAX_TILT_DEG = b
                .comment("Hard cap on total tilt, degrees. 170 so being pinned to a drum wall - or",
                        "hanging under the deck at the top of a loop - can be shown honestly. Short of",
                        "180, where a rotation vector has no defined axis.")
                .defineInRange("max_tilt_deg", 170.0, 0.0, 179.0);

        CAMERA_SLEW_DEG_PER_S = b
                .comment("Cap on how fast the camera may turn, deg/s. For the pathological case - a",
                        "contraption that snaps 180 degrees in one tick.")
                .defineInRange("slew_deg_per_s", 420.0, 30.0, 1440.0);

        CAMERA_LOOP_SUPPRESSION = b
                .comment("How much to stop following the plane once the deck goes all the way round,",
                        "0..1. DEFAULT 0 - off. It used to be 0.85, which meant the one ride the mod",
                        "exists for was the one ride the camera refused to show you. Turn it up if",
                        "full inversions make you ill; it costs you the loop, not the physics.")
                .defineInRange("loop_suppression", 0.0, 0.0, 1.0);

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
                .defineInRange("smoothing_ms", 45, 0, 1000);

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

    /**
     * How much rotation-specific behaviour applies at this spin rate, 0..1.
     *
     * <p>The plane, the stick and the climb are features of a SPINNING ride. Gating them on angular
     * velocity is what guarantees a lift or a ship under way can never trigger them however it is
     * thrown about - structural, not a tuning question.</p>
     */
    public static double spinGate(final double spin) {
        return smoothstep(spin, SPIN_DEADZONE, SPIN_FULL);
    }

    /** Dead-zone fade for the contraption's LINEAR acceleration, 0..1. Anti-noise only. */
    public static double linearAccelGate(final double magnitude) {
        return smoothstep(magnitude, LINEAR_ACCEL_DEADZONE_G * GRAVITY, LINEAR_ACCEL_FULL_G * GRAVITY);
    }

    /** How firmly the ride is holding you to the committed plane, 0..1. */
    public static double stick(final double ridePress, final double spinGate) {
        if (!WALL_ENABLED.get() || spinGate <= 0.0) {
            return 0.0;
        }

        final double physical = smoothstep(ridePress,
                WALL_MIN_PRESS_G.get() * GRAVITY, WALL_FULL_PRESS_G.get() * GRAVITY);

        // The grant and the physical press do not add: the ride either holds you because it is
        // flinging you into the surface, or because this is a ride and rides carry their riders.
        // Whichever is larger is the honest answer, and taking the max keeps the curve monotonic.
        final double granted = WALL_LOOP_ASSIST.get();

        return clamp01(Math.max(physical, granted) * spinGate * WALL_STRENGTH.get());
    }

    /** How much footing a press buys, 0..1. Used for reporting and for the friction ramp. */
    public static double footing(final double press) {
        return smoothstep(press, GRIP_MIN_PRESS_G.get() * GRAVITY, GRIP_FULL_PRESS_G.get() * GRAVITY);
    }

    public static double clamp01(final double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }

        return Math.min(1.0, Math.max(0.0, value));
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
