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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create 的所有数值框（创造马达的转速、速度控制器、过滤器……）都由
 * {@code ValueBox#submit} 用
 *
 * <pre>ms.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);</pre>
 *
 * 摆到世界空间。子关卡方块的 {@code pos} 是 <b>plot 坐标</b>（~2e7），直接当世界坐标用会把
 * 整个框连同转速数字画到视锥之外 —— 表现为“创造马达上不显示转速”。
 *
 * <p>这里改用子关卡的 renderPose：把 plot 坐标投影到世界坐标，并套上子关卡朝向，
 * 使框贴着结构里的方块（与 {@code block_decal_render/LevelRendererMixin} 对破坏裂纹的处理一致）。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.foundation.blockEntity.behaviour.ValueBox")
public class ValueBoxMixin {

    @Shadow
    protected BlockPos pos;

    @WrapOperation(
            method = "submit(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/phys/Vec3;F)V",
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"
            )
    )
    private void sable$submitAtWorldPos(final PoseStack poseStack, final double x, final double y, final double z,
                                        final Operation<Void> original,
                                        @Local(argsOnly = true) final Minecraft mc,
                                        @Local(argsOnly = true) final Vec3 camera) {
        final ClientSubLevel subLevel = this.sable$getSubLevel(mc);
        if (subLevel == null) {
            original.call(poseStack, x, y, z);
            return;
        }

        final Pose3dc renderPose = subLevel.renderPose();
        final Vec3 projectedPos = renderPose.transformPosition(Vec3.atLowerCornerOf(this.pos));

        original.call(poseStack, projectedPos.x - camera.x, projectedPos.y - camera.y, projectedPos.z - camera.z);
        // 原本的 translate 是轴对齐的；子关卡可能带朝向，补上旋转让框跟着结构转。
        poseStack.mulPose(new Matrix4f().rotate(new Quaternionf(renderPose.orientation())));
    }

    private ClientSubLevel sable$getSubLevel(final Minecraft mc) {
        final ClientLevel level = mc.level;
        if (level == null) {
            return null;
        }

        final var subLevel = Sable.HELPER.getContaining(level, this.pos);
        return subLevel instanceof final ClientSubLevel clientSubLevel ? clientSubLevel : null;
    }
}
