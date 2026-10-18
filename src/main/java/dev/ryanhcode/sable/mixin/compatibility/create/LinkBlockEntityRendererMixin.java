package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.sugar.Local;
import com.zurrtum.create.catnip.math.VecHelper;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 链路上的频率物品（发射器/接收器上下两格显示的物品）由
 * {@code LinkBlockEntityRenderer#extractRenderState} 按距离剔除：
 *
 * <pre>
 * state.link = LinkRenderer.getLinkRenderState(be, itemModelManager,
 *     be.isVirtual() ? -1 : cameraPos.distanceToSqr(VecHelper.getCenterOf(blockPos)));
 * ...
 * float max = behaviour.getRenderDistance();
 * if (max * max &lt; distance) return null;   // filterItemRenderDistance，默认 8
 * </pre>
 *
 * <p>这里的 {@code cameraPos} 是<b>世界坐标</b>的相机位置（{@code BlockEntityRenderDispatcher}
 * 的字段），而子关卡方块的 {@code blockPos} 是 <b>plot 坐标</b>（~2e7），两者相减距离恒为 2e7，
 * 远超剔除半径 —— 结果是子关卡里的链路永远返回 null，表现为“链路上的物品不显示”。
 *
 * <p>这里把方块中心按位姿投出子关卡到世界坐标后再量距离（刚体变换保距），主世界链路不受影响。
 *
 * <p>本 mixin 只在 {@code create} 已加载时生效（见 {@code AbstractSableMixinPlugin}
 * 的 {@code compatibility.<modId>} 包名约定）。
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.foundation.blockEntity.renderer.LinkBlockEntityRenderer")
public class LinkBlockEntityRendererMixin {

    /**
     * {@code extractRenderState} 只有这一处 {@code VecHelper#getCenterOf} 调用。
     */
    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zurrtum/create/catnip/math/VecHelper;getCenterOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 sable$projectLinkCenterOutOfSubLevel(final Vec3i pos,
                                                      @Local(argsOnly = true) final SmartBlockEntity be) {
        final Vec3 center = VecHelper.getCenterOf(pos);

        final Level level = be.getLevel();
        return level == null ? center : Sable.HELPER.projectOutOfSubLevel(level, center);
    }
}
