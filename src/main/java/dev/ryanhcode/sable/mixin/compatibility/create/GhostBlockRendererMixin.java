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
 * Create 的放置预览虚影（齿轮、各种方块的 ghost）由 {@code GhostBlockRenderer} 的两个实现
 * 用
 *
 * <pre>ms.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);</pre>
 *
 * 摆到世界空间。子关卡方块的 {@code pos} 是 <b>plot 坐标</b>（~2e7），直接当世界坐标用会把虚影
 * 画到视锥之外 —— 表现为“齿轮的放置预览虚影不渲染”。
 *
 * <p>改用子关卡的 renderPose：把 plot 坐标投影到世界坐标，并补上子关卡朝向，使虚影与它要
 * 预览的子关卡方块重合（写法与 {@code ValueBoxMixin}、{@code block_decal_render} 一致）。
 *
 * <p>两个内部实现类结构完全一致，故拆成两个单目标 mixin 分别处理。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 *
 * @see TransparentGhostBlockRendererMixin
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.catnip.ghostblock.GhostBlockRenderer$DefaultGhostBlockRenderer")
public class GhostBlockRendererMixin {

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
