package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.mixinhelpers.compatibility.create.SubLevelOutlinePose;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Create 的物品 outline（{@code Outliner#showItem}，过滤器里悬浮的物品等）由
 * {@code ItemOutline#submit} 按 {@code pos - camera} 摆到世界空间。
 *
 * <p>子关卡里 {@code pos} 是 plot 坐标（~2e7）、{@code camera} 是物理相机，一减就把物品画到
 * 视锥之外。这里把相机反投影回 plot 坐标系、再在 {@code PoseStack} 上乘子关卡的旋转缩放
 * （见 {@link SubLevelOutlinePose}），然后调原方法。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 *
 * @see AABBOutlineMixin
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.catnip.outliner.ItemOutline")
public class ItemOutlineMixin {

    @Shadow
    protected Vec3 pos;

    @WrapMethod(method = "submit(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/phys/Vec3;F)V")
    private void sable$projectOutlineOntoSubLevel(final Minecraft mc, final PoseStack ms, final SubmitNodeCollector queue,
                                                   final Vec3 camera, final float pt, final Operation<Void> original) {
        final ClientSubLevel subLevel = SubLevelOutlinePose.find(this.pos);

        if (subLevel == null) {
            original.call(mc, ms, queue, camera, pt);
            return;
        }

        SubLevelOutlinePose.runProjected(ms, subLevel, camera,
                plotCamera -> original.call(mc, ms, queue, plotCamera, pt));
    }
}
