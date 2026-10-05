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
 * 初次编译区块时，原版用「世界相机 + plot section」算一个 {@link TranslucencyPointOfView}，
 * 存进 {@code CompiledSectionMesh} 当作「当前这份排序是按哪个视角排的」的标记。
 *
 * <p>两个输入不同系：相机是世界坐标，section 是 plot 坐标，{@code getCoordinate} 里那个
 * {@code sectionCoord(camera) - sectionX ≈ -1.25e6} 会被 clamp 成常数 <b>-1</b>。于是这个
 * 标记跟相机无关，后面 {@code ResortTransparencyTask} 拿同样算出来的常数一比：
 * 「没变过」→ 不重排 —— 半透明方块的排序永远停在编译那一刻的视角，人一走动水/玻璃的前后
 * 关系就错了。
 *
 * <p>这里把相机先反投影到该 section 的子关卡坐标系，两个输入同系后，clamp 的结果才真的
 * 随相机移动而变化，重排判定恢复正常。
 *
 * <p>目标类是 {@code SectionRenderDispatcher} 的私有内部类，只能用 {@code targets} 字符串引用。
 *
 * @see ResortTransparencyTaskMixin
 * @see dev.ryanhcode.sable.mixin.sublevel_render.RenderSectionMixin
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskMixin {

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
