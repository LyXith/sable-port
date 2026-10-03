package dev.ryanhcode.sable.network.packets.tcp;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.client.rope.RopeManager;
import dev.ryanhcode.sable.network.tcp.SablePacketContext;
import dev.ryanhcode.sable.network.tcp.SableTCPPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 客户端：同步所有绳子（全量）。客户端收到后交给
 * {@link RopeManager} 存储并渲染。
 */
public record ClientboundRopeSyncPacket(List<Entry> ropes) implements SableTCPPacket {

    public record Entry(UUID id, List<Vec3> points, int color, float width) {
    }

    public static final Type<ClientboundRopeSyncPacket> TYPE = new Type<>(Sable.sablePath("rope_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundRopeSyncPacket> CODEC = StreamCodec.of(
            (buf, value) -> value.write(buf), ClientboundRopeSyncPacket::read);

    private static ClientboundRopeSyncPacket read(final FriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final UUID id = buf.readUUID();
            final int color = buf.readInt();
            final float width = buf.readFloat();
            final int pointCount = buf.readVarInt();
            final List<Vec3> points = new ArrayList<>(pointCount);
            for (int point = 0; point < pointCount; point++) {
                points.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
            }
            entries.add(new Entry(id, points, color, width));
        }
        return new ClientboundRopeSyncPacket(entries);
    }

    private void write(final FriendlyByteBuf buf) {
        buf.writeVarInt(this.ropes.size());
        for (final Entry entry : this.ropes) {
            buf.writeUUID(entry.id());
            buf.writeInt(entry.color());
            buf.writeFloat(entry.width());
            buf.writeVarInt(entry.points().size());
            for (final Vec3 point : entry.points()) {
                buf.writeDouble(point.x);
                buf.writeDouble(point.y);
                buf.writeDouble(point.z);
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handle(final SablePacketContext context) {
        RopeManager.sync(this.ropes);
    }
}
