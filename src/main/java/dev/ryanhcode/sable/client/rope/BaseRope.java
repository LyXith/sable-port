package dev.ryanhcode.sable.client.rope;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 客户端绳子的渲染基类。
 *
 * <p>每一段用一根<b>长方体</b>（4 个侧面）绘制，因此绳子是立体的方块长条，
 * 而不是一像素细线。可以继承并覆写 {@link #render}、{@link #getColor()}、
 * {@link #getWidth()} 或 {@link #emitBox} 来实现自定义绳子。
 */
public class BaseRope {

    protected final UUID id;
    protected final List<Vec3> points = new ArrayList<>();
    protected int color = 0xFFFFFFFF;
    /** 长方体截面半径（方块单位），实际边长约为 width * 2。 */
    protected float width = 0.15F;

    public BaseRope(final UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return this.id;
    }

    public List<Vec3> getPoints() {
        return this.points;
    }

    public void setPoints(final List<Vec3> points) {
        this.points.clear();
        this.points.addAll(points);
    }

    public int getColor() {
        return this.color;
    }

    public void setColor(final int color) {
        this.color = color;
    }

    public float getWidth() {
        return this.width;
    }

    public void setWidth(final float width) {
        this.width = width;
    }

    /**
     * 提交本帧渲染。子类可覆写以改变几何或材质。
     */
    public void render(final PoseStack poseStack, final SubmitNodeCollector collector, final Vec3 cameraPos) {
        if (this.points.size() < 2) {
            return;
        }

        final int argb = this.color;
        final float alpha = ((argb >> 24) & 0xFF) / 255.0F;
        final float red = ((argb >> 16) & 0xFF) / 255.0F;
        final float green = ((argb >> 8) & 0xFF) / 255.0F;
        final float blue = (argb & 0xFF) / 255.0F;
        final float radius = this.width;

        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, consumer) -> {
            final Vector3f from = new Vector3f();
            final Vector3f to = new Vector3f();
            final Vector3f direction = new Vector3f();
            final Vector3f side = new Vector3f();
            final Vector3f up = new Vector3f();

            for (int i = 0; i + 1 < this.points.size(); i++) {
                final Vec3 p0 = this.points.get(i);
                final Vec3 p1 = this.points.get(i + 1);

                from.set((float) (p0.x - cameraPos.x), (float) (p0.y - cameraPos.y), (float) (p0.z - cameraPos.z));
                to.set((float) (p1.x - cameraPos.x), (float) (p1.y - cameraPos.y), (float) (p1.z - cameraPos.z));

                direction.set(to).sub(from);
                if (direction.lengthSquared() < 1.0E-9F) {
                    continue;
                }
                direction.normalize();

                // 正交基：side x up == direction
                side.set(direction).cross(0.0F, 1.0F, 0.0F);
                if (side.lengthSquared() < 1.0E-6F) {
                    side.set(direction).cross(1.0F, 0.0F, 0.0F);
                }
                side.normalize().mul(radius);

                up.set(side).cross(direction).normalize().mul(radius);

                this.emitBox(consumer, pose, from, to, side, up, red, green, blue, alpha);
            }
        });
    }

    /**
     * 写入一段长方体（4 个侧面）。子类可覆写以自定义形状。
     */
    protected void emitBox(final VertexConsumer consumer, final PoseStack.Pose pose,
                           final Vector3f from, final Vector3f to,
                           final Vector3f side, final Vector3f up,
                           final float red, final float green, final float blue, final float alpha) {
        // 8 个角
        final float a0x = from.x + side.x + up.x, a0y = from.y + side.y + up.y, a0z = from.z + side.z + up.z;
        final float a1x = from.x + side.x - up.x, a1y = from.y + side.y - up.y, a1z = from.z + side.z - up.z;
        final float a2x = from.x - side.x - up.x, a2y = from.y - side.y - up.y, a2z = from.z - side.z - up.z;
        final float a3x = from.x - side.x + up.x, a3y = from.y - side.y + up.y, a3z = from.z - side.z + up.z;

        final float b0x = to.x + side.x + up.x, b0y = to.y + side.y + up.y, b0z = to.z + side.z + up.z;
        final float b1x = to.x + side.x - up.x, b1y = to.y + side.y - up.y, b1z = to.z + side.z - up.z;
        final float b2x = to.x - side.x - up.x, b2y = to.y - side.y - up.y, b2z = to.z - side.z - up.z;
        final float b3x = to.x - side.x + up.x, b3y = to.y - side.y + up.y, b3z = to.z - side.z + up.z;

        // +side 面
        this.emitQuad(consumer, pose, a0x, a0y, a0z, a1x, a1y, a1z, b1x, b1y, b1z, b0x, b0y, b0z, red, green, blue, alpha);
        // -up 面（底）
        this.emitQuad(consumer, pose, a1x, a1y, a1z, a2x, a2y, a2z, b2x, b2y, b2z, b1x, b1y, b1z, red, green, blue, alpha);
        // -side 面
        this.emitQuad(consumer, pose, a2x, a2y, a2z, a3x, a3y, a3z, b3x, b3y, b3z, b2x, b2y, b2z, red, green, blue, alpha);
        // +up 面（顶）
        this.emitQuad(consumer, pose, a3x, a3y, a3z, a0x, a0y, a0z, b0x, b0y, b0z, b3x, b3y, b3z, red, green, blue, alpha);
    }

    protected void emitQuad(final VertexConsumer consumer, final PoseStack.Pose pose,
                            final float x0, final float y0, final float z0,
                            final float x1, final float y1, final float z1,
                            final float x2, final float y2, final float z2,
                            final float x3, final float y3, final float z3,
                            final float red, final float green, final float blue, final float alpha) {
        consumer.addVertex(pose, x0, y0, z0).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x2, y2, z2).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, x3, y3, z3).setColor(red, green, blue, alpha);
    }
}
