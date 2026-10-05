package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinhelpers.compatibility.create.SubLevelOutlinePose;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Create 的线段 outline（{@code Outliner#showLine} / {@code endChasingLine}，锁链连接时画的
 * 两条线、各种连接线）由 {@code LineOutline#submitInner} 按 {@code start - camera} 摆到世界空间。
 *
 * <p>子关卡里 {@code start} 是 plot 坐标（~2e7）、{@code camera} 是物理相机，一减就把整条线
 * 画到视锥之外。这里把相机反投影回 plot 坐标系、再在 {@code PoseStack} 上乘子关卡的旋转缩放
 * （见 {@link SubLevelOutlinePose}），然后调原方法；{@code EndChasingLineOutline#submitInner}
 * 会转调 {@code super}，所以一并被覆盖。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 *
 * @see AABBOutlineMixin
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.catnip.outliner.LineOutline")
public class LineOutlineMixin {

    @WrapMethod(method = "submitInner(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/phys/Vec3;FLorg/joml/Vector3d;Lorg/joml/Vector3d;FIIZ)V")
    private void sable$projectOutlineOntoSubLevel(final PoseStack ms, final SubmitNodeCollector queue, final Vec3 camera,
                                                   final float pt, final Vector3d start, final Vector3d end,
                                                   final float width, final int color, final int lightmap,
                                                   final boolean disableNormals, final Operation<Void> original) {
        // 连接线常有一端探到子关卡区块边界外（例如贴着结构边缘的锁链），起点查不到就再试终点。
        ClientSubLevel subLevel = SubLevelOutlinePose.find(new Vec3(start.x, start.y, start.z));

        if (subLevel == null) {
            subLevel = SubLevelOutlinePose.find(new Vec3(end.x, end.y, end.z));
        }

        if (subLevel == null) {
            original.call(ms, queue, camera, pt, start, end, width, color, lightmap, disableNormals);
            return;
        }

        SubLevelOutlinePose.runProjected(ms, subLevel, camera,
                plotCamera -> original.call(ms, queue, plotCamera, pt, start, end, width, color, lightmap, disableNormals));
    }
}
