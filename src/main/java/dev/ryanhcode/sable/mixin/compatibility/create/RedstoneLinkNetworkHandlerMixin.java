package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.zurrtum.create.content.redstone.link.IRedstoneLinkable;
import com.zurrtum.create.infrastructure.config.AllConfigs;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 无线红石链路的配对判定：
 *
 * <pre>
 * Set&lt;IRedstoneLinkable&gt; network = getNetworkOf(world, actor);   // 按频率分组，与位置无关
 * ...
 * if (!withinRange(actor, other)) continue;
 * </pre>
 *
 * <p>网络本身按“频率”而非位置分组，所以只要距离校验放行，子关卡里的链路和主世界里的链路
 * 本就落在同一个集合里。唯一的门槛是 {@code withinRange}：
 *
 * <pre>from.getLocation().closerThan(to.getLocation(), linkRange)</pre>
 *
 * <p>子关卡方块的 {@code getLocation()} 是 <b>plot 坐标</b>（默认 plotyard 起点即 2e7 量级），
 * 而主世界链路是<b>世界坐标</b>，两者直接相减距离恒为 2e7，远大于 256 的默认链路半径 ——
 * 结果是跨系的链路永远配不上，表现为“机械动力无线红石不能在子关卡上使用”。
 *
 * <p>这里把两端各自按位姿投出子关卡到世界坐标后再比（刚体变换保距），同系时与原判据完全一致，
 * 跨系时才等于“两个链路世界真实位置的距离”，语义与原版一致。
 *
 * <p>本 mixin 只在 {@code create} 已加载时生效（见 {@code AbstractSableMixinPlugin}
 * 的 {@code compatibility.<modId>} 包名约定）。
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.content.redstone.link.RedstoneLinkNetworkHandler")
public class RedstoneLinkNetworkHandlerMixin {

    /**
     * 目标方法是 {@code updateNetworkOf} 里被调用的 static 方法，回调与目标方法（非 static）保持一致即可；
     * {@code world} 由 {@code @Local(argsOnly = true)} 从 {@code updateNetworkOf} 的入参捕获。
     */
    @WrapOperation(
            method = "updateNetworkOf",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zurrtum/create/content/redstone/link/RedstoneLinkNetworkHandler;withinRange(Lcom/zurrtum/create/content/redstone/link/IRedstoneLinkable;Lcom/zurrtum/create/content/redstone/link/IRedstoneLinkable;)Z"
            )
    )
    private boolean sable$poseAwareRangeCheck(final IRedstoneLinkable from, final IRedstoneLinkable to,
                                              final Operation<Boolean> original,
                                              @Local(argsOnly = true) final LevelAccessor world) {
        if (original.call(from, to)) {
            return true;
        }

        if (!(world instanceof final Level level)) {
            return false;
        }

        // BlockPos#closerThan 用的是角点距离，这里保持同样的语义。
        final double range = AllConfigs.server().logistics.linkRange.get();
        final Position fromPos = Vec3.atLowerCornerOf(from.getLocation());
        final Position toPos = Vec3.atLowerCornerOf(to.getLocation());

        return Sable.HELPER.distanceSquaredWithSubLevels(level, fromPos, toPos) < range * range;
    }
}
