package dev.ryanhcode.sable.mixin.plot;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableCommonEvents;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hooks into setBlockState to notify plots & plot chunk holders of block changes.
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {

    @Shadow
    @Final
    private Level level;

    @Unique
    private BlockPos sable$blockSet = null;

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void sable$preSetBlockState(final BlockPos pPos, final BlockState pState, final int pFlags,
                                        final CallbackInfoReturnable<BlockState> cir) {
        this.sable$blockSet = pPos;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void sable$postSetBlockState(final BlockPos pPos, final BlockState pState, final int pFlags,
                                         final CallbackInfoReturnable<BlockState> cir) {
        if (this.sable$blockSet != null) {
            final SubLevel subLevel = Sable.HELPER.getContaining(this.level, this.sable$blockSet);

            if (subLevel != null) {
                subLevel.getPlot().onBlockChange(this.sable$blockSet, pState);
            }
        }
        this.sable$blockSet = null;
    }

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState sable$setBlockState(final LevelChunkSection instance, int pX, int pY, int pZ, final BlockState newState, final Operation<BlockState> original) {
        final BlockState oldState = original.call(instance, pX, pY, pZ, newState);

        if (this.level instanceof final ServerLevel serverLevel && oldState != newState) {
            pX = this.sable$blockSet.getX();
            pY = this.sable$blockSet.getY();
            pZ = this.sable$blockSet.getZ();

            SableCommonEvents.handleBlockChange(serverLevel, (LevelChunk) (Object) this, pX, pY, pZ, oldState, newState);
        }

        return oldState;
    }

    /**
     * 当前这个 LevelChunk 是不是 sable 的子关卡(plot)区块。
     * 只有 plot 区块启用下面的「保留方块实体实例」逻辑，非 plot 区块完全走原版路径，控制影响面。
     */
    @Unique
    private boolean sable$isPlotChunk() {
        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        return container != null && container.inBounds(((LevelChunk) (Object) this).getPos());
    }

    /**
     * 子关卡里任意一个方块变化，服务端就把整个 plot 区块重发一次
     * ({@code PlotChunkHolder.networkDirty} → {@code SubLevelTrackingSystem.sendChunkUpdates} →
     * {@code ClientboundLevelChunkWithLightPacket})，客户端收到后走 {@link LevelChunk#replaceWithPacketData}。
     *
     * <p>该方法开头会 {@code clearAllBlockEntities()} 把区块里**所有**方块实体销毁重建，于是每个实例上
     * 只存在于客户端的私有状态都会归零 —— 最典型的是 Create 飞轮的 {@code FlywheelBlockEntity.angle}
     * (每客户端 tick 自己累加、不进 read/write、服务端那份恒为 0)，表现就是「轮子转到一半突然跳回初始角度」；
     * 其它累加状态量如 {@code MechanicalBearingBlockEntity.angle} 同理。
     *
     * <p><b>而原版在重建回调里本来就写了类型一致性检查</b>：
     * {@code lambda$replaceWithPacketData$0} 里 {@code getBlockEntity(pos, IMMEDIATE)} →
     * {@code be.getType() == packetType} 才 {@code loadWithComponents(...)}。
     * 也就是说只要不销毁，原生代码会自动把包里的新数据灌进旧实例 —— 我们只需补两件事：
     * <ol>
     *   <li>{@link #sable$pruneStaleBlockEntities} —— 包里已不存在/类型已变的旧 BE 先删掉，否则会残留错误类型的 BE；</li>
     *   <li>{@link #sable$resyncBlockStates} —— 幸存 BE 的内部 blockState 对齐到新状态
     *       (原版是靠「销毁重建」保证这一点的)。</li>
     * </ol>
     *
     * <p>顺带收益：Create 客户端的 {@code Storage.visuals} 按 BE 对象身份索引，
     * 实例存活就不会触发 remove+add，省掉每 tick 的 visual 重建抖动。
     *
     * <p>落点：{@code 13: invokevirtual clearAllBlockEntities:()V}（javap 逐字节核对）。
     * 该方法全项目只有这一个调用点，target 不匹配会在加载期直接报错(defaultRequire=1)，不会静默失效。
     */
    @WrapOperation(
        method = "replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;clearAllBlockEntities()V")
    )
    private void sable$keepPlotBlockEntities(final LevelChunk self, final Operation<Void> original) {
        if (this.sable$isPlotChunk()) {
            return; // plot 区块：不清空，保留实例
        }

        original.call(self);
    }

    /**
     * 必须在 {@code clearAllBlockEntities} 之前跑(字节码 offset 13 之前)：
     * 按包里声明的 (pos → BE 类型) 剪掉不该继续存在的旧 BE。
     * 剪完之后原版的 {@code forEachBlockEntityTag} 对这些位置会走 {@code createBlockEntity} 建新实例，
     * 结果与原版「先全清再重建」完全一致 —— 只是**类型没变的位置不再重建**。
     *
     * <p>{@code data} 里声明的就是「应用完之后应当存在的全部 BE」，所以按它剪出来的后态
     * 与原版清空重建出来的后态等价(区别仅在实例身份)。
     */
    @Inject(
        method = "replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
        at = @At("HEAD")
    )
    private void sable$pruneStaleBlockEntities(final int chunkX, final int chunkZ,
                                                final ClientboundLevelChunkPacketData data,
                                                final CallbackInfo ci) {
        if (!this.sable$isPlotChunk()) {
            return;
        }

        final LevelChunk self = (LevelChunk) (Object) this;

        final Map<BlockPos, BlockEntityType<?>> wanted = new HashMap<>();
        data.forEachBlockEntityTag(chunkX, chunkZ, (pos, type, tag) -> wanted.put(pos.immutable(), type));

        // 先拷 keySet，removeBlockEntity 会改这个 map
        for (final BlockPos pos : List.copyOf(self.getBlockEntities().keySet())) {
            final BlockEntity old = self.getBlockEntity(pos); // CHECK 模式，不会创建

            if (old == null) {
                continue;
            }

            // 类型一致 → 保留实例(关键)；类型已变或包里没有 → 删掉
            if (wanted.get(pos) == old.getType()) {
                continue;
            }

            self.removeBlockEntity(pos); // setRemoved -> Create 等自行清渲染
        }
    }

    /**
     * 幸存 BE 的内部 {@code blockState} 对齐到重建后的新方块状态。
     * 原版靠「销毁重建」保证这一点，我们保留了实例就必须自己补 —— 否则同一个 BlockEntityType
     * 下换了方块变体(如转动/换朝向)的 BE 会拿着过期的 state 去渲染和碰撞。
     *
     * <p>语义对齐原版 {@code LevelChunk.setBlockState}：仅当 state 变了且新 state 合法时才写。
     * {@code replaceWithPacketData} 全项目只有 offset 134 一个 return。
     */
    @Inject(
        method = "replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
        at = @At("RETURN")
    )
    private void sable$resyncBlockStates(final int chunkX, final int chunkZ,
                                         final ClientboundLevelChunkPacketData data,
                                         final CallbackInfo ci) {
        if (!this.sable$isPlotChunk()) {
            return;
        }

        final LevelChunk self = (LevelChunk) (Object) this;

        for (final Map.Entry<BlockPos, BlockEntity> entry : self.getBlockEntities().entrySet()) {
            final BlockEntity be = entry.getValue();
            final BlockState state = self.getBlockState(entry.getKey());

            if (be.getBlockState() != state && be.isValidBlockState(state)) {
                be.setBlockState(state);
            }
        }
    }

}
