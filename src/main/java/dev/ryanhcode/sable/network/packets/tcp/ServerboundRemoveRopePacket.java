package dev.ryanhcode.sable.network.packets.tcp;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.network.tcp.SablePacketContext;
import dev.ryanhcode.sable.network.tcp.SableTCPPacket;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * 客户端 → 服务端：请求移除（左键打掉）指定绳子。
 */
public record ServerboundRemoveRopePacket(UUID id) implements SableTCPPacket {

    public static final Type<ServerboundRemoveRopePacket> TYPE = new Type<>(Sable.sablePath("remove_rope"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundRemoveRopePacket> CODEC = StreamCodec.of(
            (buf, value) -> value.write(buf), ServerboundRemoveRopePacket::read);

    private static ServerboundRemoveRopePacket read(final FriendlyByteBuf buf) {
        return new ServerboundRemoveRopePacket(buf.readUUID());
    }

    private void write(final FriendlyByteBuf buf) {
        buf.writeUUID(this.id);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    @Override
    public void handle(final SablePacketContext context) {
        if (!(context.level() instanceof final ServerLevel serverLevel)) {
            return;
        }

        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(serverLevel);
        if (system == null) {
            return;
        }

        ArbitraryPhysicsObject target = null;
        for (final ArbitraryPhysicsObject object : system.getArbitraryObjects()) {
            if (object instanceof final RopePhysicsObject rope && rope.getUUID().equals(this.id)) {
                target = object;
                break;
            }
        }

        if (target != null) {
            system.removeObject(target);
        }
    }
}
