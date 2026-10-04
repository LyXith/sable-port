package dev.ryanhcode.sable.mixin.entity.entity_rotations_and_riding;

import dev.ryanhcode.sable.mixinterface.entity.entity_rotations_and_riding.EntityTransformationExtension;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityTransformationExtension {
    @Unique
    private Quaternionf sable$orientation;

    @Unique
    private Vector3f sable$scale;

    @Unique
    private Vector3f sable$pivot;

    @Override
    public void sable$setSubLevelOrientation(Quaternionf orientation) {
        this.sable$orientation = orientation;
    }

    @Override
    public void sable$setSubLevelScale(Vector3f scale) {
        this.sable$scale = scale;
    }

    @Override
    public void sable$setSubLevelPivot(Vector3f pivot) {
        this.sable$pivot = pivot;
    }

    @Override
    public Quaternionf sable$getSubLevelOrientation() {
        return this.sable$orientation;
    }

    @Override
    public Vector3f sable$getSubLevelScale() {
        return this.sable$scale;
    }

    @Override
    public Vector3f sable$getSubLevelPivot() {
        return this.sable$pivot;
    }
}
