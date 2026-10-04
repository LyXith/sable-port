package dev.ryanhcode.sable.mixin.entity.entity_rotations_and_riding;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinterface.entity.entity_rotations_and_riding.EntityTransformationExtension;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @WrapOperation(method = "submitEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void sable$rotateEntity(
            EntityRenderDispatcher instance,
            EntityRenderState renderState,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            Operation<Void> original
    ) {
        final EntityTransformationExtension transformation = (EntityTransformationExtension) renderState;
        final Quaternionf orientation = transformation.sable$getSubLevelOrientation();
        final Vector3f scale = transformation.sable$getSubLevelScale();
        final Vector3f pivot = transformation.sable$getSubLevelPivot();

        poseStack.pushPose();

        poseStack.translate(x, y, z);
        poseStack.translate(pivot.x, pivot.y, pivot.z);
        poseStack.scale(scale.x, scale.y, scale.z);
        poseStack.rotate(orientation);
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z);
        poseStack.translate(-x, -y, -z);

        original.call(instance, renderState, camera, x, y, z, poseStack, submitNodeCollector);
        poseStack.popPose();
    }
}
