package com.playsi.centrifugal_force.internal;

final class AdhesionSettings {
    static final int ATTACH_ALIGN_TICKS = 4;
    static final int CORNER_TICKS = 12;
    static final int ARC_SAMPLES = 6;
    static final int SUPPORT_GRACE_TICKS = 3;
    static final int REATTACH_COOLDOWN_TICKS = 10;

    static final double ADHESION_PULL = 0.08;
    static final double PERPENDICULAR_TOLERANCE = 0.15;
    static final double CORNER_CLEARANCE = 0.02;
    static final double CONTACT_REACH = 0.8;
    static final double ATTACH_REACH = 0.75;
    static final double ATTACH_SINK_TOLERANCE = 0.15;
    static final double NEAR_SUPPORT_GAP = 0.45;
    static final double JUMP_SUPPORT_GAP = 1.3;

    private AdhesionSettings() {}
}
