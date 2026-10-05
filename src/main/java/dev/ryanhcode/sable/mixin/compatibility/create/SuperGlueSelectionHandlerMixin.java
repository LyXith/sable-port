package dev.ryanhcode.sable.mixin.compatibility.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.mixinhelpers.compatibility.create.SubLevelOutlinePose;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * 强力胶的「指向已有胶 → 高亮 + 左键删除」判定在 {@code SuperGlueSelectionHandler#tick} 里：
 *
 * <pre>
 * Vec3 traceOrigin = player.getEyePosition();          // 玩家在世界坐标上
 * Optional&lt;Vec3&gt; clip = glueEntity.getBoundingBox()    // 子关卡实体存在 plot 坐标上
 *                          .clip(traceOrigin, traceTarget);
 * </pre>
 *
 * <p>子关卡里的 {@code SuperGlueEntity} 存在于所在 level 的 <b>plot 坐标</b>（~2e7），
 * 而玩家（含眼睛）在<b>世界坐标</b>上 —— 两者由 {@code SubLevelInclusiveLevelEntityGetter}
 * 负责桥接查询，但这里是一次纯几何 {@code AABB#clip}，谁也不会换系：射线在 ~0、框在 ~2e7，
 * 永不相交 → {@code selected} 恒为 null。于是一个根因带出三个症状：</p>
 *
 * <ul>
 *   <li>框的线画了（{@code glueNearby} 能查到），但颜色恒为 {@code PASSIVE} 而不是高亮色
 *       —— “颜色不对”；</li>
 *   <li>面贴图 {@code faceTex = h ? GLUE : null} 依赖 {@code glueEntity == selected}，
 *       恒 null —— “面没了”；</li>
 *   <li>{@code onMouseInput} 里 {@code selected == null} 直接返回 false，删除包根本不发 ——
 *       “左键打不掉”。</li>
 * </ul>
 *
 * <p>修法：把胶的包围盒按它所属子关卡的渲染位姿投影回世界坐标再 clip。这样 clip 结果、
 * 随后 {@code vec3.distanceToSqr(traceOrigin)} 的最近者排序、以及 {@code soundSourceForRemoval}
 * 都留在世界坐标 —— 与服务端 {@code player.level()} 里玩家的世界坐标一致。</p>
 *
 * <p>仅在 {@code create} 已加载时生效（{@code compatibility.<modId>} 包名约定）。</p>
 *
 * @see SubLevelOutlinePose
 */
@Pseudo
@Mixin(targets = "com.zurrtum.create.client.content.contraptions.glue.SuperGlueSelectionHandler")
public class SuperGlueSelectionHandlerMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/client/Minecraft;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;"
            )
    )
    private Optional<Vec3> sable$clipGlueInWorldSpace(final AABB box, final Vec3 from, final Vec3 to,
                                                       final Operation<Optional<Vec3>> original) {
        final ClientSubLevel subLevel = SubLevelOutlinePose.find(box.getCenter());

        if (subLevel == null) {
            // 胶不在任何子关卡里（主世界），坐标本就同系
            return original.call(box, from, to);
        }

        if (SubLevelOutlinePose.find(from) != null) {
            // 射线原点已经在 plot 空间（与胶同系），无需换系
            return original.call(box, from, to);
        }

        return original.call(SubLevelOutlinePose.projectToWorld(subLevel, box), from, to);
    }
}
