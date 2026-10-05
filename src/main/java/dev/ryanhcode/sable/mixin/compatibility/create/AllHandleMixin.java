package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create 的服务端配置包（马达转速、速度控制器、阈值开关、过滤器……共 30 多个处理器）
 * 全部经由 {@code AllHandle#onBlockEntityConfiguration} 做 reach 校验：
 *
 * <pre>if (!pos.closerThan(player.blockPosition(), distance)) return;</pre>
 *
 * <p>子关卡方块的 {@code pos} 是 <b>plot 坐标</b>（默认 plotyard 起点即 2e7 量级），
 * 而 {@code player.blockPosition()} 是<b>世界坐标</b>，两者直接相减距离恒为 2e7，
 * 远大于 20 的校验半径 —— 结果是服务端无条件拒绝所有 Create 配置交互，
 * 表现为“创造马达无法调节”。
 *
 * <p>这里把玩家位置按子关卡位姿逆变换到 plot 空间后再比较。刚体变换保距，
 * 因此结果等价于“玩家到该结构世界真实位置的距离”，语义与原版一致：
 * 玩家离结构很远时依旧会被拒绝。
 *
 * <p>本 mixin 只在 {@code create} 已加载时生效（见 {@code AbstractSableMixinPlugin}
 * 的 {@code compatibility.<modId>} 包名约定）。
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.AllHandle")
public class AllHandleMixin {

    @WrapOperation(
            method = "onBlockEntityConfiguration(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/core/BlockPos;ILjava/util/function/Predicate;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;closerThan(Lnet/minecraft/core/Vec3i;D)Z"
            )
    )
    /**
     * 目标方法是 static，因此本回调也必须声明为 static（Mixin 不允许 static 目标用非 static 回调）。
     */
    private static boolean sable$poseAwareReachCheck(final BlockPos pos, final Vec3i playerPos, final double distance,
                                                     final Operation<Boolean> original,
                                                     @Local(argsOnly = true) final ServerGamePacketListenerImpl listener) {
        if (original.call(pos, playerPos, distance)) {
            return true;
        }

        final SubLevel subLevel = Sable.HELPER.getContaining(listener.player.level(), pos);
        if (subLevel == null) {
            return false;
        }

        // 世界坐标 → plot 坐标；与 clip_overwrite/BlockGetterMixin 的子关卡射线投影保持一致。
        final Pose3dc pose = subLevel.logicalPose();
        final Vector3dc localPlayer = pose.transformPositionInverse(JOMLConversion.toJOML(Vec3.atLowerCornerOf(playerPos)));

        // Vec3i#closerThan 用的是 distSqr（角点距离），这里保持同样的语义。
        final double dx = pos.getX() - localPlayer.x();
        final double dy = pos.getY() - localPlayer.y();
        final double dz = pos.getZ() - localPlayer.z();

        return dx * dx + dy * dy + dz * dz < distance * distance;
    }

    /**
     * 删除强力胶的 reach 校验：
     *
     * <pre>
     * double range = 32;
     * if (player.distanceToSqr(superGlue.position()) &gt; range * range) return;
     * </pre>
     *
     * <p>玩家在<b>世界坐标</b>上，子关卡里的 {@code SuperGlueEntity.position()} 是 <b>plot 坐标</b>
     * （~2e7），直接相减恒为 2e7 &gt; 32² —— 即使客户端成功发来了删除包，服务端也无条件 return，
     * 表现为“左键打不掉强力胶”。</p>
     *
     * <p>换成 {@code distanceSquaredWithSubLevels}：它把两个点各自投出子关卡到世界坐标后再比，
     * 刚体变换保距，因此同系时结果与原判据完全一致，跨系时才等于“玩家到结构世界真实位置的
     * 距离”，语义与原版一致。客户端对应的换系见
     * {@link SuperGlueSelectionHandlerMixin}（还要保证 {@code soundSource} 是世界坐标，
     * 与这里 {@code player.level()} 的坐标系一致）。
     *
     * <p>目标方法是 static，因此本回调也必须声明为 static。
     */
    @WrapOperation(
            method = "onSuperGlueRemoval(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lcom/zurrtum/create/infrastructure/packet/c2s/SuperGlueRemovalPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"
            )
    )
    private static double sable$poseAwareGlueRemovalReach(final ServerPlayer player, final Vec3 gluePos,
                                                           final Operation<Double> original) {
        final double direct = original.call(player, gluePos);

        if (direct <= 32 * 32) {
            // 已经同系且够近（例如胶在主世界、或玩家就在同一 plot 里）
            return direct;
        }

        return Sable.HELPER.distanceSquaredWithSubLevels(player.level(), (Position) player.position(), (Position) gluePos);
    }
}
