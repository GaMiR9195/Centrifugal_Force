package com.playsi.aero_cam_sync.apiimpl;

import com.playsi.aero_cam_sync.api.AcsRay;
import net.minecraft.world.phys.Vec3;

/** Луч из снимка. Направление хранится готовым — его считает вызывающий, а не мы повторно. */
record AcsRayImpl(Vec3 from, Vec3 to, Vec3 direction) implements AcsRay {

    static AcsRay of(Vec3 from, Vec3 direction, double reach) {
        return new AcsRayImpl(from, from.add(direction.scale(reach)), direction);
    }
}
