package dev.ryanhcode.sable.mixin.sublevel_render;

import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 打开移动方块渲染的邻接面剔除。
 *
 * <p>原版 {@code MovingBlockFeatureRenderer#buildGroup} 构造
 * {@code ModelBlockRenderer} 时把 {@code cull} 传成 {@code false}，因为活塞的移动方块没有
 * 邻居、不需要剔除。但 Sable 把子关卡方块也走这条路，于是每个方块 6 个面全画，实心结构
 * 内部的面也画，飞到内部就会看到本该剔除的面。
 *
 * <p>这里强制 {@code cull = true}：对活塞来说 {@code MovingBlockRenderState.getBlockState}
 * 对任何邻居都返回空气，剔除判断结果为“要画”，行为不变；对子关卡方块，则会使用
 * {@code SubLevelMovingBlockRenderState} 从 plot 读取真实邻居，从而正确剔除内部面并计算
 * 正确的环境光遮蔽。
 */
@Mixin(MovingBlockFeatureRenderer.class)
public class MovingBlockFeatureRendererMixin {

    @ModifyArg(
            method = "buildGroup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;<init>(ZZLnet/minecraft/client/color/block/BlockColors;)V"
            ),
            index = 1
    )
    private boolean sable$forceFaceCulling(final boolean cull) {
        return true;
    }
}
