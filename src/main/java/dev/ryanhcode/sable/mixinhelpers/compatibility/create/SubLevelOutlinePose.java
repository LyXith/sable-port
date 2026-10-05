package dev.ryanhcode.sable.mixinhelpers.compatibility.create;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Position;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Create 的 outline（强力胶选区、锁链连接、线、物品……）统一按
 *
 * <pre>世界坐标 - 物理相机</pre>
 *
 * 提交，其中“世界坐标”取的是方块在<b>所在关卡</b>里的坐标。子关卡里的坐标是
 * <b>plot 坐标</b>（~2e7），而 {@code LevelRenderer} 传下来的相机是结构所在位置的物理相机
 * （sable 自己给子关卡地形/方块实体/悬停框用的也是它，见 {@code sublevel_render} 里的
 * {@code T(position - camera) * R * S}），两者一减就得到一个 ~2e7 的偏移，整条 outline
 * 被甩到视锥之外 —— 表现为“子维度上看不到强力胶的选区”。
 *
 * <p>修法是<b>前乘</b>（先改相机，再乘线性部分），而不是后乘：</p>
 *
 * <ol>
 *   <li>把物理相机反投影回子关卡的 plot 坐标系：{@code camera' = renderPose.transformPositionInverse(camera)}，
 *       此时 {@code renderPose(camera') == camera}；</li>
 *   <li>在 {@code PoseStack} 上只乘 renderPose 的线性部分 {@code L = R * S}
 *       （{@code new Matrix4f().rotate(orientation).scale(scale)}）。</li>
 * </ol>
 *
 * <p>于是 Create 自己算出的 {@code X - camera'} 是<b>同系坐标的小差值</b>（几格），
 * 再乘 {@code L} 得到 {@code L·X - L·camera'}，由第 1 条有
 * {@code L·camera' + t = camera}，即结果 {@code= L·X + t - camera = renderPose(X) - camera} ——
 * 正是“把 plot 坐标投影到世界坐标再减物理相机”，且全程没有 2e7 级别的大数相消。</p>
 *
 * <p><b>为什么不能后乘</b>：后乘要算 {@code L·(X - camera) + (renderPose(camera) - camera)}，
 * 其中 {@code X - camera ≈ 2e7}、{@code renderPose(camera) - camera ≈ -2e7}，两者在
 * {@link Matrix4f} 的 float 精度下相消（2e7 处 float 步长是 2），结果误差 ±2~4 格且随相机
 * 移动而抖动 —— 表现为“框的线在乱飞”。</p>
 *
 * <p>仅在 {@code create} 已加载的调用链上使用（{@code compatibility.<modId>} 包名约定）。</p>
 *
 * @see dev.ryanhcode.sable.mixin.compatibility.create.AABBOutlineMixin
 */
public final class SubLevelOutlinePose {

    private SubLevelOutlinePose() {
    }

    /**
     * @return 包含该世界坐标点的子关卡；不在任何子关卡里（例如主世界坐标）返回 null
     */
    public static @Nullable ClientSubLevel find(final Position pos) {
        final ClientLevel level = Minecraft.getInstance().level;

        if (level == null) {
            return null;
        }

        final SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        return subLevel instanceof final ClientSubLevel clientSubLevel ? clientSubLevel : null;
    }

    /**
     * 选区框可能跨子关卡的区块边界，中心点查不到时再退到两个角点。
     */
    public static @Nullable ClientSubLevel find(final AABB box) {
        final ClientSubLevel center = find(box.getCenter());

        if (center != null) {
            return center;
        }

        final ClientSubLevel min = find(new Vec3(box.minX, box.minY, box.minZ));
        return min != null ? min : find(new Vec3(box.maxX, box.maxY, box.maxZ));
    }

    /**
     * @return 物理相机在子关卡 plot 坐标系里的对应点，用它替换 Create 拿到的 {@code camera}
     */
    public static Vec3 plotCamera(final Pose3dc pose, final Vec3 camera) {
        return pose.transformPositionInverse(camera);
    }

    /**
     * 在 {@code ms} 上乘 renderPose 的线性部分（旋转 + 缩放）。必须配合 {@link #plotCamera} 使用。
     */
    public static void apply(final Pose3dc pose, final PoseStack ms) {
        ms.mulPose(new Matrix4f().rotate(new Quaternionf(pose.orientation()))
                .scale((float) pose.scale().x(), (float) pose.scale().y(), (float) pose.scale().z()));
    }

    /**
     * 把子关卡里的 AABB（plot 坐标）投影回世界坐标。
     *
     * <p>子关卡的实体存在<b>所在 level 的 plot 坐标</b>上，而玩家本身在<b>世界坐标</b>上
     * （两者由 {@code SubLevelInclusiveLevelEntityGetter} 桥接查询）。因此任何拿“玩家的
     * 世界坐标”直接去和“实体的 plot 坐标”做几何比较的地方（射线 clip、距离校验）都要先换系，
     * 否则恒不相交 / 恒距离 2e7。
     *
     * <p>刚体变换保距，投影到世界后做距离比较与在 plot 空间里比较等价。
     */
    public static AABB projectToWorld(final ClientSubLevel subLevel, final AABB box) {
        return new BoundingBox3d(box).transform(subLevel.renderPose()).toMojang();
    }

    /**
     * 给自己不 push/pop 的方法用：包一层姿势，乘上线性部分，把反投影后的相机交给 body，
     * 跑完再还原（异常安全）。
     */
    public static void runProjected(final PoseStack ms, final ClientSubLevel subLevel, final Vec3 camera, final Consumer<Vec3> body) {
        final Pose3dc pose = subLevel.renderPose();
        ms.pushPose();

        try {
            apply(pose, ms);
            body.accept(pose.transformPositionInverse(camera));
        } finally {
            ms.popPose();
        }
    }
}
