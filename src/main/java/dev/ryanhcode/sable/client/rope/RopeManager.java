package dev.ryanhcode.sable.client.rope;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundRopeSyncPacket;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端绳子的注册表与渲染入口。
 *
 * <p>服务端通过 {@link ClientboundRopeSyncPacket} 同步绳子点集；这里负责存储与
 * 每帧提交渲染。绳子本身用 {@link BaseRope}（可继承）表示。
 */
public final class RopeManager {

    private static final Map<UUID, BaseRope> ROPES = new Object2ObjectOpenHashMap<>();

    private RopeManager() {
    }

    /**
     * 用服务端同步的全量数据替换本地绳子集。
     */
    public static void sync(final List<ClientboundRopeSyncPacket.Entry> entries) {
        final List<UUID> present = new ArrayList<>(entries.size());
        for (final ClientboundRopeSyncPacket.Entry entry : entries) {
            present.add(entry.id());
            final BaseRope rope = ROPES.computeIfAbsent(entry.id(), RopeManager::createRope);
            rope.setPoints(entry.points());
            rope.setColor(entry.color());
            rope.setWidth(entry.width());
        }
        ROPES.keySet().removeIf(id -> !present.contains(id));
    }

    /**
     * 工厂方法：可覆写/替换以创建自定义 {@link BaseRope} 子类。
     */
    public static BaseRope createRope(final UUID id) {
        return new BaseRope(id);
    }

    public static void remove(final UUID id) {
        ROPES.remove(id);
    }

    public static void clear() {
        ROPES.clear();
    }

    public static Collection<BaseRope> getRopes() {
        return ROPES.values();
    }

    public static void renderAll(final PoseStack poseStack, final SubmitNodeCollector collector, final Vec3 cameraPos) {
        for (final BaseRope rope : ROPES.values()) {
            rope.render(poseStack, collector, cameraPos);
        }
    }

    /**
     * 返回沿射线命中的最近绳子（用于左键打掉），未命中返回 {@code null}。
     */
    public static UUID pick(final Vec3 origin, final Vec3 direction, final double range) {
        final Vec3 end = origin.add(direction.scale(range));
        UUID best = null;
        double bestDistance = Double.MAX_VALUE;
        final double threshold = 0.8;

        for (final BaseRope rope : ROPES.values()) {
            final List<Vec3> points = rope.getPoints();
            for (int i = 0; i + 1 < points.size(); i++) {
                final double distance = segmentDistance(origin, end, points.get(i), points.get(i + 1));
                if (distance < threshold && distance < bestDistance) {
                    bestDistance = distance;
                    best = rope.getId();
                }
            }
        }

        return best;
    }

    /**
     * 两条线段之间的最近距离（Ericson, Real-Time Collision Detection）。
     */
    private static double segmentDistance(final Vec3 p1, final Vec3 q1, final Vec3 p2, final Vec3 q2) {
        final double d1x = q1.x - p1.x, d1y = q1.y - p1.y, d1z = q1.z - p1.z;
        final double d2x = q2.x - p2.x, d2y = q2.y - p2.y, d2z = q2.z - p2.z;
        final double rx = p1.x - p2.x, ry = p1.y - p2.y, rz = p1.z - p2.z;

        final double a = d1x * d1x + d1y * d1y + d1z * d1z;
        final double e = d2x * d2x + d2y * d2y + d2z * d2z;
        final double f = d2x * rx + d2y * ry + d2z * rz;

        double s;
        double t;

        if (a <= 1.0E-12 && e <= 1.0E-12) {
            return Math.sqrt(rx * rx + ry * ry + rz * rz);
        }

        if (a <= 1.0E-12) {
            s = 0.0;
            t = clamp(f / e, 0.0, 1.0);
        } else {
            final double c = d1x * rx + d1y * ry + d1z * rz;
            if (e <= 1.0E-12) {
                t = 0.0;
                s = clamp(-c / a, 0.0, 1.0);
            } else {
                final double b = d1x * d2x + d1y * d2y + d1z * d2z;
                final double denom = a * e - b * b;
                s = denom != 0.0 ? clamp((b * f - c * e) / denom, 0.0, 1.0) : 0.0;
                t = (b * s + f) / e;
                if (t < 0.0) {
                    t = 0.0;
                    s = clamp(-c / a, 0.0, 1.0);
                } else if (t > 1.0) {
                    t = 1.0;
                    s = clamp((b - c) / a, 0.0, 1.0);
                }
            }
        }

        final double cx = (p1.x + d1x * s) - (p2.x + d2x * t);
        final double cy = (p1.y + d1y * s) - (p2.y + d2y * t);
        final double cz = (p1.z + d1z * s) - (p2.z + d2z * t);
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static double clamp(final double value, final double min, final double max) {
        return value < min ? min : Math.min(value, max);
    }
}
