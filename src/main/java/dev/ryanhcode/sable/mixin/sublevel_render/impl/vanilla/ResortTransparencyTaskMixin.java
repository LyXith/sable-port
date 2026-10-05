package dev.ryanhcode.sable.mixin.sublevel_render.impl.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.SubLevelSectionSortHelper;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 重排半透明面片时，原版用 {@code TranslucencyPointOfView} 判断「视角变了没有」：
 *
 * <pre>
 * if (!mesh.isDifferentPointOfView(pov) && !pov.isAxisAligned()) return CANCELLED;
 * </pre>
 *
 * <p>子关卡的 section 是 plot 坐标，拿世界相机算出来的 {@code sectionCoord(camera) - sectionX}
 * 是个 ~1.25e6 的负数，被 {@code getCoordinate} clamp 成常数 <b>-1</b>（y 轴同理）。于是这个
 * 「视角标记」既跟相机无关，也和上一次存进去的常数一模一样 → 判定为没变 → <b>CANCELLED，
 * 永不重排</b>；而 {@code isAxisAligned} 只有某一轴 clamp 到 0 时才放行。
 *
 * <p>表现是：水/玻璃的前后关系停在区块第一次排序时的视角上，人一走动就错 —— 比如「站在水面
 * 上看不到水里的粘液块」。
 *
 * <p>把相机先反投影到该 section 的子关卡坐标系后，标记随相机移动正常变化，重排恢复。
 * 排序方向本身由 {@link dev.ryanhcode.sable.mixin.sublevel_render.RenderSectionMixin} 负责，
 * 两者必须同系，否则「判据放行了，键却是错的」。
 *
 * <p>目标类是 {@code SectionRenderDispatcher} 的私有内部类，只能用 {@code targets} 字符串引用。
 *
 * @see CompileTaskMixin
 * @see dev.ryanhcode.sable.mixin.sublevel_render.RenderSectionMixin
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask")
public abstract class ResortTransparencyTaskMixin {

    @WrapOperation(
            method = "doTask(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;of(Lnet/minecraft/world/phys/Vec3;J)Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;")
    )
    private TranslucencyPointOfView sable$plotSpacePointOfView(final Vec3 cameraPos, final long sectionNode,
                                                               final Operation<TranslucencyPointOfView> original) {
        return original.call(SubLevelSectionSortHelper.plotCamera(SectionPos.of(sectionNode), cameraPos), sectionNode);
    }
}
