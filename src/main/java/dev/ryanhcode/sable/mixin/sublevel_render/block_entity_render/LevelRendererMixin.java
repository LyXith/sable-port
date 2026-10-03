package dev.ryanhcode.sable.mixin.sublevel_render.block_entity_render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Unique
    public final Map<BlockEntityRenderState, SableBlockEntityTransform> sable$blockEntityTransforms = new IdentityHashMap<>();

    @Unique
    private Quaternionf sable$cameraOrientation;


    @Inject(method = "submitBlockEntities", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", shift = At.Shift.AFTER))
    public void sable$transformBlockEntity(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci, @Local final BlockEntityRenderState renderState, @Local final BlockPos blockPos) {
        final SableBlockEntityTransform transform = this.sable$blockEntityTransforms.remove(renderState);
        if (transform == null) {
            return;
        }

        final Vec3 cameraPosition = levelRenderState.cameraRenderState.pos;
        poseStack.translate(
                -(blockPos.getX() - cameraPosition.x),
                -(blockPos.getY() - cameraPosition.y),
                -(blockPos.getZ() - cameraPosition.z)
        );
        poseStack.mulPose(transform.transformation());
        poseStack.translate(
                blockPos.getX() - transform.rotationPoint().x(),
                blockPos.getY() - transform.rotationPoint().y(),
                blockPos.getZ() - transform.rotationPoint().z()
        );

        this.sable$cameraOrientation = new Quaternionf(levelRenderState.cameraRenderState.orientation);
        levelRenderState.cameraRenderState.orientation = new Quaternionf(transform.orientation()).conjugate().mul(levelRenderState.cameraRenderState.orientation);
    }

    @Inject(method = "submitBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", shift = At.Shift.AFTER))
    private void sable$restoreCameraOrientation(PoseStack poseStack, net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState, SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        if (this.sable$cameraOrientation != null) {
            levelRenderState.cameraRenderState.orientation = this.sable$cameraOrientation;
            this.sable$cameraOrientation = null;
        }
    }

    @Unique
    record SableBlockEntityTransform(Matrix4f transformation, Vector3d rotationPoint, Quaternionf orientation) {
        private SableBlockEntityTransform(final Matrix4f transformation, final Vector3dc rotationPoint, final Quaternionf orientation) {
            this(new Matrix4f(transformation), new Vector3d(rotationPoint), new Quaternionf(orientation));
        }
    }
}
