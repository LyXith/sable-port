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

    @Override
    public void sable$setSubLevelOrientation(Quaternionf orientation) {
        this.sable$orientation = orientation;
    }

    @Override
    public void sable$setSubLevelScale(Vector3f scale) {
        this.sable$scale = scale;
    }

    @Override
    public Quaternionf sable$getSubLevelOrientation() {
        return this.sable$orientation;
    }

    @Override
    public Vector3f sable$getSubLevelScale() {
        return this.sable$scale;
    }
}
