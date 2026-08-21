package com.playsi.aero_cam_sync.client.camera;

import net.minecraft.world.level.ClipContext;

public class LevelClipMixinState {
    public static boolean inTiltedClip = false;
    public static ClipContext tiltedContext = null;
}
