package dev.ryanhcode.sable.sublevel.render;

import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 用于渲染子关卡单个方块的 {@link MovingBlockRenderState}。
 *
 * <p>原版 {@link MovingBlockRenderState#getBlockState(BlockPos)} 只认自身坐标，对任何邻居都
 * 返回空气。配合 {@code MovingBlockFeatureRendererMixin} 强制打开的 {@code cull}，这里从
 * 子关卡所在的 plot 关卡读取真实邻居，从而让 {@code ModelBlockRenderer} 正确剔除内部面并
 * 计算环境光遮蔽。
 */
public class SubLevelMovingBlockRenderState extends MovingBlockRenderState {

    @Nullable
    private Level level;

    public void sable$setLevel(final Level level) {
        this.level = level;
    }

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        if (this.level == null) {
            return super.getBlockState(pos);
        }

        return this.level.getBlockState(pos);
    }
}
