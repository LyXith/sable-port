package dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

/**
 * 把「世界坐标里的相机」换算到某个 plot section 所在的子关卡坐标系。
 *
 * <p>子关卡的 section 原点是 <b>plot 坐标</b>（~2e7），而 {@code SectionRenderDispatcher} 上的
 * {@code cameraPosition} 是结构所在位置的物理相机（世界坐标）。原版拿这两者直接做减法得到
 * 半透明的排序键（{@code camera - sectionOrigin}）与「视角是否变了」的判据
 * （{@code TranslucencyPointOfView}），放到子关卡里就变成了 {@code 世界 - plot ≈ -2e7}：
 * 排序键失去意义，判据则被 clamp 成一个和相机无关的常数，于是「重排」永远判定为没变。
 *
 * <p>调用点都在区块编译线程上（{@code CompileTask}/{@code ResortTransparencyTask}），因此用
 * {@link SubLevel#logicalPose()} 而不是 {@code renderPose()}：后者会写入它上次求值的 partial
 * tick，会和渲染线程抢；logicalPose 只落后一 tick，带来的键偏移远不到一格。
 *
 * @see dev.ryanhcode.sable.mixin.sublevel_render.RenderSectionMixin
 */
public final class SubLevelSectionSortHelper {

    private SubLevelSectionSortHelper() {
    }

    /**
     * @return 该 section 属于子关卡时反投影到 plot 坐标系的相机；属于主世界时原样返回
     */
    public static Vec3 plotCamera(final SectionPos sectionPos, final Vec3 cameraPos) {
        final ClientLevel level = Minecraft.getInstance().level;

        if (level == null) {
            return cameraPos;
        }

        final SubLevel subLevel = Sable.HELPER.getContaining(level, sectionPos);

        if (subLevel == null) {
            return cameraPos;
        }

        return subLevel.logicalPose().transformPositionInverse(cameraPos);
    }
}
