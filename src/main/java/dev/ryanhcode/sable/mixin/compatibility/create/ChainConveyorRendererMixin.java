package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.mixinhelpers.compatibility.create.SubLevelOutlinePose;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 锁链传动（链传送带）的链身由 {@code ChainConveyorRenderer#getChainsRenderState} 决定用
 * 近处的 {@code ChainRenderState}（整段贴图 + 动画）还是远处的 {@code FarChainRenderState}：
 *
 * <pre>boolean far = renderWorld && !cameraPos.closerThan(chain中点, MIP_DISTANCE);</pre>
 *
 * 这里的 {@code cameraPos} 是<b>物理相机</b>，而链中点是子关卡的 <b>plot 坐标</b>（~2e7），
 * 在子维度里两者恒差 2e7 &gt; 48，于是永远判定为 far：
 * {@code FarChainRenderState} 只用 {@code maxV = 0.0625f} 采一行贴图、截面也更细，
 * 看上去就是「锁链连接上只有一条线，没有锁链的纹理」。
 *
 * <p>这里把距离比较放到 plot 坐标系里做（相机反投影回 plot），只影响 LOD 判定，
 * 不改几何 —— 链身几何本身就是 {@code stats.start() - tilePos} 的方块相对量，
 * 配合 {@code sublevel_render} 给方块实体套的 pose 是对的。
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。
 *
 * @see SubLevelOutlinePose
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.content.kinetics.chainConveyor.ChainConveyorRenderer")
public class ChainConveyorRendererMixin {

    @WrapOperation(
            method = "getChainsRenderState(Lcom/zurrtum/create/content/kinetics/chainConveyor/ChainConveyorBlockEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;closerThan(Lnet/minecraft/core/Position;D)Z")
    )
    private boolean sable$compareDistanceInPlotSpace(final Vec3 instance, final Position position, final double distance,
                                                      final Operation<Boolean> original,
                                                      @Local(argsOnly = true) final BlockPos tilePos) {
        final ClientSubLevel subLevel = SubLevelOutlinePose.find(Vec3.atLowerCornerOf(tilePos));

        if (subLevel == null) {
            return original.call(instance, position, distance);
        }

        return original.call(SubLevelOutlinePose.plotCamera(subLevel.renderPose(), instance), position, distance);
    }
}
