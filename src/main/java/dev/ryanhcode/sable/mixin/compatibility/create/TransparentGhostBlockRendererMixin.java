package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code GhostBlockRenderer$TransparentGhostBlockRenderer} —— 放置预览（齿轮等的虚影）走的实现。
 *
 * <p>与 {@link GhostBlockRendererMixin} 完全同源同修法：{@code ms.translate(pos - camera)} 里的
 * {@code pos} 是 plot 坐标，需要投影到世界坐标并补子关卡朝向。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.catnip.ghostblock.GhostBlockRenderer$TransparentGhostBlockRenderer")
public class TransparentGhostBlockRendererMixin {

    @WrapOperation(
            method = "render(Lnet/minecraft/client/renderer/block/BlockStateModelSet;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/phys/Vec3;Lcom/zurrtum/create/client/catnip/ghostblock/GhostBlockParams;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"
            )
    )
    private void sable$projectGhostOntoSubLevel(final PoseStack poseStack, final double x, final double y, final double z,
                                                 final Operation<Void> original,
                                                 @Local(argsOnly = true) final Vec3 camera,
                                                 @Local final BlockPos pos) {
        final ClientSubLevel subLevel = this.sable$getSubLevel(pos);
        if (subLevel == null) {
            original.call(poseStack, x, y, z);
            return;
        }

        final Pose3dc renderPose = subLevel.renderPose();
        final Vec3 projectedPos = renderPose.transformPosition(Vec3.atLowerCornerOf(pos));

        original.call(poseStack, projectedPos.x - camera.x, projectedPos.y - camera.y, projectedPos.z - camera.z);
        // 原本的 translate 是轴对齐的；子关卡可能带朝向，补上旋转让虚影贴到结构上。
        poseStack.mulPose(new Matrix4f().rotate(new Quaternionf(renderPose.orientation())));
    }

    private ClientSubLevel sable$getSubLevel(final BlockPos pos) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }

        final var subLevel = Sable.HELPER.getContaining(level, pos);
        return subLevel instanceof final ClientSubLevel clientSubLevel ? clientSubLevel : null;
    }
}
