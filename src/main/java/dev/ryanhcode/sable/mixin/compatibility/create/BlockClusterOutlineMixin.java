package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinhelpers.compatibility.create.SubLevelOutlinePose;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create 的方块簇 outline（强力胶选中的那串方块）由 {@code BlockClusterOutline} 用
 *
 * <pre>ms.translate(anchor.getX() - camera.x, ...);</pre>
 *
 * 摆到世界空间。子关卡里 {@code anchor} 是 plot 坐标（~2e7）、{@code camera} 是物理相机，
 * 一减就把整簇线框画到视锥之外 —— 与 {@link AABBOutlineMixin} 是同一个 bug 的两半。
 *
 * <p>这两个方法自己就 {@code pushPose}/{@code popPose}，所以：在它 push 之后先乘子关卡的
 * 旋转缩放，再把 {@code translate} 的参数换成 {@code anchor - 反投影后的相机}（两边同为 plot
 * 坐标，差值只有几格，不会在 float 精度下大数相消），见 {@link SubLevelOutlinePose}。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 *
 * @see AABBOutlineMixin
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.catnip.outliner.BlockClusterOutline")
public class BlockClusterOutlineMixin {

    @WrapOperation(
            method = "submitFaces",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V")
    )
    private void sable$projectFacesOntoSubLevel(final PoseStack ms, final double x, final double y, final double z,
                                                 final Operation<Void> original,
                                                 @Local(argsOnly = true) final Vec3 camera,
                                                 @Local final BlockPos anchor) {
        final ClientSubLevel subLevel = SubLevelOutlinePose.find(Vec3.atLowerCornerOf(anchor));

        if (subLevel == null) {
            original.call(ms, x, y, z);
            return;
        }

        final var pose = subLevel.renderPose();
        SubLevelOutlinePose.apply(pose, ms);

        final Vec3 plotCamera = SubLevelOutlinePose.plotCamera(pose, camera);
        original.call(ms, anchor.getX() - plotCamera.x, anchor.getY() - plotCamera.y, anchor.getZ() - plotCamera.z);
    }

    @WrapOperation(
            method = "submitEdges",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V")
    )
    private void sable$projectEdgesOntoSubLevel(final PoseStack ms, final double x, final double y, final double z,
                                                 final Operation<Void> original,
                                                 @Local(argsOnly = true) final Vec3 camera,
                                                 @Local final BlockPos anchor) {
        final ClientSubLevel subLevel = SubLevelOutlinePose.find(Vec3.atLowerCornerOf(anchor));

        if (subLevel == null) {
            original.call(ms, x, y, z);
            return;
        }

        final var pose = subLevel.renderPose();
        SubLevelOutlinePose.apply(pose, ms);

        final Vec3 plotCamera = SubLevelOutlinePose.plotCamera(pose, camera);
        original.call(ms, anchor.getX() - plotCamera.x, anchor.getY() - plotCamera.y, anchor.getZ() - plotCamera.z);
    }
}
