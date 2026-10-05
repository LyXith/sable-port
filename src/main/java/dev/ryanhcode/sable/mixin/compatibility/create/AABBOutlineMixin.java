package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinhelpers.compatibility.create.SubLevelOutlinePose;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Create 的 AABB outline（强力胶的选区框、粘住的胶体、各种高亮框）由
 * {@code AABBOutline#submitBox} 把框按 {@code box - camera} 摆到世界空间，
 * {@code ChasingAABBOutline}（{@code Outliner#showAABB} 实际创建的类型）也复用它。
 *
 * <p>子关卡里 {@code box} 是 plot 坐标（~2e7）、{@code camera} 是物理相机，一减就把整框
 * 画到视锥之外 —— 表现为“子维度上看不到强力胶的选区”。这里把相机反投影回 plot 坐标系、
 * 再在 {@code PoseStack} 上乘子关卡的旋转缩放（见 {@link SubLevelOutlinePose}），然后调原方法。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 *
 * @see BlockClusterOutlineMixin
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.catnip.outliner.AABBOutline")
public class AABBOutlineMixin {

    @WrapMethod(method = "submitBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;IIZ)V")
    private void sable$projectOutlineOntoSubLevel(final PoseStack ms, final SubmitNodeCollector queue, final Vec3 camera,
                                                   final AABB box, final int color, final int lightmap,
                                                   final boolean disableLineNormals, final Operation<Void> original) {
        final ClientSubLevel subLevel = SubLevelOutlinePose.find(box);

        if (subLevel == null) {
            original.call(ms, queue, camera, box, color, lightmap, disableLineNormals);
            return;
        }

        SubLevelOutlinePose.runProjected(ms, subLevel, camera,
                plotCamera -> original.call(ms, queue, plotCamera, box, color, lightmap, disableLineNormals));
    }
}
