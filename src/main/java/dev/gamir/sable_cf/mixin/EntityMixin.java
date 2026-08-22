package dev.gamir.sable_cf.mixin;

import dev.gamir.sable_cf.physics.BodyFrame;
import dev.gamir.sable_cf.physics.BodyFrameHolder;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds a {@link BodyFrame} field to every entity. That is all it does.
 *
 * <h2>Why there is no longer a {@code makeBoundingBox} injection</h2>
 *
 * <p>There used to be one, re-fitting the rotated body into an axis-aligned box. It has been
 * removed, and reading Sable's collision code is what settled it:</p>
 *
 * <ul>
 *   <li>{@code SubLevelEntityCollision} builds its oriented box from the entity's <i>unrotated</i>
 *       {@code getXsize/getYsize/getZsize} plus the quaternion from
 *       {@code EntitySubLevelUtil.getCustomEntityOrientation}, and runs SAT against sub-level
 *       blocks. Supplying the orientation is therefore the entire job; the box is genuinely
 *       rotated, not approximated.</li>
 *   <li>It expands its own broadphase by the eye height, so nothing downstream needs to widen
 *       anything to be seen.</li>
 *   <li>It pivots the body about eye height ({@code eyeHeight - bbHeight/2}), not the feet, so a
 *       feet-pivoted enclosing box positively disagreed with Sable's own.</li>
 *   <li>{@code collide()} returns early for {@code ServerPlayer}. The server does no sub-level
 *       collision for players at all, so a widened vanilla box could only ever act against
 *       <i>main-level</i> geometry - which is precisely how it wedged players in corridors and had
 *       the server shove them out.</li>
 * </ul>
 *
 * <p>So the widening was not a trade-off that came with rotation; it was a fourth thing that was
 * never needed. Nothing is inflated anywhere now, and the wedging caveat is gone with it.</p>
 *
 * <p>The field lives on the entity rather than in a map keyed by entity because it is read from
 * both threads on a hot path; a field on the object it describes cannot be contended and cannot
 * leak when the entity unloads.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements BodyFrameHolder {

    @Unique
    @Nullable
    private BodyFrame sable_cf$frame;

    @Override
    public BodyFrame sable_cf$bodyFrame() {
        if (this.sable_cf$frame == null) {
            this.sable_cf$frame = new BodyFrame();
        }

        return this.sable_cf$frame;
    }

    @Override
    @Nullable
    public BodyFrame sable_cf$bodyFrameOrNull() {
        return this.sable_cf$frame;
    }
}
