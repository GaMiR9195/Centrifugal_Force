package dev.gamir.sable_cf.physics;

import org.jetbrains.annotations.Nullable;

/**
 * Duck interface implemented on every {@code Entity} by {@code EntityMixin}.
 *
 * <p>Storing the body frame on the entity rather than in a map keyed by entity is not a style
 * choice: the hitbox is refitted from both the client thread and the server thread, and a shared
 * map would need locking on a path that runs several times per entity per tick. A field on the
 * object it describes cannot be contended and cannot leak when the entity is unloaded.</p>
 */
public interface BodyFrameHolder {

    /** The entity's body frame, created on first use. */
    BodyFrame sable_cf$bodyFrame();

    /** The body frame if one was ever created, else null. Cheap enough for the hitbox path. */
    @Nullable
    BodyFrame sable_cf$bodyFrameOrNull();
}
