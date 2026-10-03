package dev.ryanhcode.sable.mixin.block_decal_render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Changes the distance block damage is rendered from, and transforms block damage rendering for sublevels.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", shift = At.Shift.AFTER))
    private void sable$transformBlockDamage(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final LevelRenderState levelRenderState, final CallbackInfo ci, @Local final BlockBreakingRenderState renderState, @Local final BlockPos pos) {
        final Vec3 plotPos = Vec3.atLowerCornerOf(pos);
        final ClientSubLevel subLevel = sable$getSubLevel(pos);
        if (subLevel == null) {
            return;
        }

        final Pose3dc renderPose = subLevel.renderPose();
        final Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        final Vec3 projectedPos = renderPose.transformPosition(plotPos);

        // 原版这次 translate 以 plot 坐标（量级 ~2e7）为基准。抵消它，改用子关卡的世界坐标，
        // 再应用子关卡的朝向，使破坏裂纹贴到子关卡方块上（与子关卡方块自身的渲染变换等价）。
        poseStack.translate(
                -(plotPos.x - cameraPos.x),
                -(plotPos.y - cameraPos.y),
                -(plotPos.z - cameraPos.z)
        );
        poseStack.translate(projectedPos.x - cameraPos.x, projectedPos.y - cameraPos.y, projectedPos.z - cameraPos.z);
        poseStack.mulPose(new Matrix4f().rotate(new Quaternionf(renderPose.orientation())));
    }

    /**
     * 原版 {@code submitBreakingBlockModel(..., boolean)} 的最后一个参数决定裂纹进哪个渲染阶段：
     * {@code true} → {@code breakingOverlay}（在 {@code solid} 之后绘制），{@code false} → {@code solid}。
     *
     * <p>普通方块的裂纹进 {@code solid} 没有问题，因为地形在 chunk pass 里更早绘制、裂纹仍然覆盖在上；
     * 但 Sable 的子关卡方块是通过 {@code submitMovingBlock} 提交到 {@code solid}，而
     * {@code MovingBlockFeatureRenderer} 在 {@code BlockModelFeatureRenderer} 之后绘制，
     * 于是子关卡方块会把裂纹盖住。这里对子关卡方块强制置 {@code true}，让裂纹进
     * {@code breakingOverlay}，从而盖在子关卡方块之上。
     */
    @WrapOperation(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"))
    private void sable$submitBlockDamageOverBlocks(final SubmitNodeCollector collector, final PoseStack poseStack, final List<BlockStateModelPart> parts, final int progress, final boolean materialFlag, final Operation<Void> original, @Local final BlockBreakingRenderState renderState) {
        final boolean overlay = materialFlag || sable$getSubLevel(renderState.blockPos()) != null;
        original.call(collector, poseStack, parts, progress, overlay);
    }

    @Unique
    private static ClientSubLevel sable$getSubLevel(final BlockPos pos) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return (ClientSubLevel) Sable.HELPER.getContaining(level, Vec3.atLowerCornerOf(pos));
    }
}
