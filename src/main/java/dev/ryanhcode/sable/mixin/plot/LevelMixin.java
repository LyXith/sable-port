package dev.ryanhcode.sable.mixin.plot;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在向 plot 坐标写入方块之前，确保目标区块已经在 plot 中创建。
 *
 * <p>子关卡交互（放置方块）是通过射线投射把世界坐标映射到 plot 坐标后，直接调用
 * {@link Level#setBlock} 完成的。如果目标位置恰好落在 plot 内、但该区块尚未创建
 * （典型场景就是在区块边界处放置），{@code ServerChunkCacheMixin} 会返回一个占位空区块，
 * 方块写进去后随即丢失，表现为“区块边界处放不出去”。
 *
 * <p>这里只在真正要写方块时补齐区块（读路径不创建），并且只处理 plot 网格内、确实属于某个
 * plot 的区块，避免像之前那样在网格外创建越界区块覆盖数据。
 */
@Mixin(Level.class)
public class LevelMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void sable$ensurePlotChunk(final BlockPos blockPos, final BlockState blockState, final int flags, final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
        final Level level = (Level) (Object) this;
        if (level.isClientSide()) {
            return;
        }

        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final ChunkPos chunkPos = new ChunkPos(blockPos.getX() >> 4, blockPos.getZ() >> 4);
        if (!container.inBounds(chunkPos)) {
            return;
        }

        final LevelPlot plot = container.getPlot(chunkPos);
        if (plot == null) {
            return;
        }

        final ChunkPos local = plot.toLocal(chunkPos);
        if (plot.getChunkHolder(local) == null) {
            plot.newEmptyChunk(chunkPos);
        }
    }
}
