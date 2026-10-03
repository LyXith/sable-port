package dev.ryanhcode.sable.sublevel.system;

import dev.ryanhcode.sable.SableConfig;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.api.sublevel.SubLevelTrackingPlugin;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket;
import dev.ryanhcode.sable.network.packets.tcp.*;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.network.tcp.SablePacketSink;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.*;

/**
 * Handles the loading and unloading of {@link SubLevel SubLevels} for {@link ServerPlayer ServerPlayers}.
 */
public class SubLevelTrackingSystem implements SubLevelObserver {
    private final ServerLevel level;
    private final List<SubLevel> additionQueue = new ObjectArrayList<>();
    private final Set<UUID> currentlyUpdatingPlayers = new ObjectOpenHashSet<>();
    private final Set<UUID> pluginNeededPlayers = new ObjectOpenHashSet<>();
    private final List<SubLevelTrackingPlugin> plugins = new ObjectArrayList<>();

    /**
     * 记录每个 plot 已经下发过（通过完整同步或增量）的区块，用于发现后来才创建的区块，
     * 以及避免重复下发。
     */
    private final Map<LevelPlot, LongOpenHashSet> syncedChunks = new Object2ObjectOpenHashMap<>();

    private int interpolationTick;
    private long lastSendMs = -1;

    public SubLevelTrackingSystem(final ServerLevel level) {
        this.level = level;
    }

    private static long getSubLevelLong(final ServerSubLevel subLevel, final SubLevelContainer subLevels) {
        final Vector2i origin = subLevels.getOrigin();
        final ChunkPos plotPos = subLevel.getPlot().plotPos;
        return ChunkPos.pack(plotPos.x - origin.x(), plotPos.z - origin.y);
    }

    private boolean shouldLoad(final Player player, final Vector3dc entityPosition) {
        final double trackingRange = SableConfig.SUB_LEVEL_TRACKING_RANGE.getAsDouble();
        return entityPosition.distanceSquared(player.getX(), player.getY(), player.getZ()) < trackingRange * trackingRange;
    }

    @Override
    public void onSubLevelAdded(final SubLevel subLevel) {
        this.additionQueue.add(subLevel);
    }

    @Override
    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
        this.additionQueue.remove(subLevel);
        this.syncedChunks.remove(subLevel.getPlot());
        final ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;
        this.sendRemoval(this.serverWidePlayerSink(serverSubLevel), serverSubLevel);
    }

    public SablePacketSink serverWidePlayerSink(final ServerSubLevel serverSubLevel) {
        // PORT-NOTE(mc26.1): SablePacketSink (de-Veil shim) hands over raw CustomPacketPayloads,
        // which must be wrapped in ClientboundCustomPayloadPacket before sending.
        return payloads -> {
            for (final UUID uuid : serverSubLevel.getTrackingPlayers()) {
                final ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(uuid);

                if (player != null) {
                    for (final CustomPacketPayload payload : payloads) {
                        player.connection.send(new ClientboundCustomPayloadPacket(payload));
                    }
                }
            }
        };
    }

    private void collectPlayers(final Vector3d position, final Collection<UUID> tracking) {
        for (final ServerPlayer player : this.level.players()) {
            if (this.shouldLoad(player, position)) {
                tracking.add(player.getGameProfile().id());
            }
        }
    }

    private void sendFullSync(final ServerPlayer player, final ServerSubLevel subLevel, @Nullable final CustomPacketPayload extraPacket) {
        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        assert container != null;

        final long l = getSubLevelLong(subLevel, container);

        final LevelPlot plot = subLevel.getPlot();

        final Collection<PlotChunkHolder> chunks = plot.getLoadedChunks();
        final ObjectList<Packet<? super ClientGamePacketListener>> packets = new ObjectArrayList<>(3 + chunks.size());

        packets.add(new ClientboundCustomPayloadPacket(new ClientboundStartTrackingSubLevelPacket(l, subLevel.getUniqueId(), subLevel.lastPose(), subLevel.logicalPose(), plot.getBoundingBox(), subLevel.getName(), this.interpolationTick)));

        if (extraPacket != null) {
            packets.add(new ClientboundCustomPayloadPacket(extraPacket));
        }

        final LongOpenHashSet synced = this.syncedChunks.computeIfAbsent(plot, x -> new LongOpenHashSet());

        for (final PlotChunkHolder holder : chunks) {
            final LevelChunk chunk = holder.getChunk();
            SubLevelPlayerChunkSender.sendChunk(packets::add, plot.getLightEngine(), chunk);
            synced.add(ChunkPos.pack(chunk.getPos().x, chunk.getPos().z));
            // Intentionally do NOT clear networkDirty here: sendChunkUpdates runs
            // later in the same tick and re-sends any chunk whose blocks/light
            // changed, which guarantees the freshly-populated plot reaches the
            // client even if this full sync raced the block placement.
        }

        packets.add(new ClientboundCustomPayloadPacket(new ClientboundFinalizeSubLevelPacket(l)));
        player.connection.send(new ClientboundBundlePacket(packets));

        for (final PlotChunkHolder chunk : chunks) {
            SubLevelPlayerChunkSender.sendChunkPoiData(this.level, chunk.getChunk());
        }
    }

    private void sendRemoval(final SablePacketSink sink, final ServerSubLevel subLevel) {
        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        assert container != null;

        final long l = getSubLevelLong(subLevel, container);
        sink.sendPacket(new ClientboundStopTrackingSubLevelPacket(l));
    }

    @Override
    public void tick(final SubLevelContainer container) {
        for (final SubLevel subLevel : this.additionQueue) {
            // If the sub-level has been removed before we could even send it to clients, skip it
            if (subLevel.isRemoved()) {
                continue;
            }

            final ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;

            final Collection<UUID> tracking = serverSubLevel.getTrackingPlayers();
            final Vector3d position = subLevel.logicalPose().position();

            this.collectPlayers(position, tracking);

            final UUID splitFromSubLevelID = serverSubLevel.getSplitFromSubLevel();
            final SubLevel splitFromSubLevel = splitFromSubLevelID != null ? container.getSubLevel(splitFromSubLevelID) : null;

            for (final UUID uuid : tracking) {
                final ServerPlayer player = (ServerPlayer) this.level.getPlayerByUUID(uuid);

                if (player == null) {
                    throw new IllegalStateException("Player not found immediately after tracking initializes");
                }

                CustomPacketPayload extraPacket = null;

                if (splitFromSubLevelID != null && splitFromSubLevel != null) {
                    extraPacket = new ClientboundRecentlySplitSubLevelPacket(
                            serverSubLevel.getUniqueId(),
                            splitFromSubLevel.getUniqueId(),
                            serverSubLevel.getSplitFromPose()
                    );
                }

                this.sendFullSync(player, serverSubLevel, extraPacket);
            }

            serverSubLevel.clearSplitFrom();
        }
        this.additionQueue.clear();

        for (final SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            final ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;

            final Collection<UUID> tracking = serverSubLevel.getTrackingPlayers();
            final Vector3dc entityPos = subLevel.logicalPose().position();

            final Iterator<UUID> iter = tracking.iterator();
            while (iter.hasNext()) {
                final UUID uuid = iter.next();

                final ServerPlayer player = (ServerPlayer) this.level.getPlayerByUUID(uuid);

                if (player == null) {
                    // player has been removed
                    final ServerPlayer serverWidePlayer = this.level.getServer().getPlayerList().getPlayer(uuid);

                    if (serverWidePlayer != null) {
                        // they are still online, just not in this world
                        this.sendRemoval(SablePacketSink.player(serverWidePlayer), serverSubLevel);
                    }

                    iter.remove();
                    continue;
                }

                if (!this.shouldLoad(player, entityPos)) {
                    this.sendRemoval(SablePacketSink.player(player), serverSubLevel);
                    iter.remove();
                }
            }

            // add players who SHOULD be tracking but aren't
            for (final ServerPlayer player : this.level.players()) {
                final UUID uuid = player.getGameProfile().id();
                if (this.shouldLoad(player, entityPos) && !tracking.contains(uuid)) {
                    tracking.add(uuid);
                    this.sendFullSync(player, serverSubLevel, null);
                }
            }
        }

        // send positional updates separately
        this.sendBoundsUpdates(container);
        this.sendChunkUpdates(container);
        this.sendMovementUpdates(container);
    }

    /**
     * 把新建的、或内容已经变化（例如在区块边界放置方块）的 plot 区块增量下发到正在追踪该子关卡的客户端。
     * <p>
     * 完整同步（{@link #sendFullSync}）只在玩家开始追踪时发生，之后客户端不会自动收到新创建区块的数据，
     * 因此需要在这里补齐，否则会出现“服务端放下了、客户端要重进才看得到”的现象。
     */
    private void sendChunkUpdates(final SubLevelContainer container) {
        for (final SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved() || !(subLevel instanceof final ServerSubLevel serverSubLevel)) {
                continue;
            }

            final Collection<UUID> tracking = serverSubLevel.getTrackingPlayers();
            if (tracking.isEmpty()) {
                continue;
            }

            final LevelPlot plot = subLevel.getPlot();
            final LongOpenHashSet synced = this.syncedChunks.computeIfAbsent(plot, x -> new LongOpenHashSet());

            for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
                final LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }

                final long key = ChunkPos.pack(chunk.getPos().x, chunk.getPos().z);
                final boolean isNew = !synced.contains(key);

                if (!isNew && !holder.isNetworkDirty()) {
                    continue;
                }

                this.sendChunkToTrackingPlayers(plot, chunk, tracking);
                synced.add(key);
                holder.clearNetworkDirty();
            }

            // 清理已经不再加载的区块，避免集合无限增长
            synced.retainAll(this.collectLoadedChunkKeys(plot));
        }
    }

    private LongOpenHashSet collectLoadedChunkKeys(final LevelPlot plot) {
        final LongOpenHashSet keys = new LongOpenHashSet();
        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk != null) {
                keys.add(ChunkPos.pack(chunk.getPos().x, chunk.getPos().z));
            }
        }
        return keys;
    }

    private void sendChunkToTrackingPlayers(final LevelPlot plot, final LevelChunk chunk, final Collection<UUID> tracking) {
        for (final UUID uuid : tracking) {
            final ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) {
                SubLevelPlayerChunkSender.sendChunk(player.connection::send, plot.getLightEngine(), chunk);
            }
        }
    }

    /**
     * Sends updates regarding sub-level plot bound changes to all tracking players
     *
     * @param container the sublevels to send updates for
     */
    private void sendBoundsUpdates(final SubLevelContainer container) {
        for (final SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            final ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;

            final BoundingBox3ic plotBounds = serverSubLevel.getPlot().getBoundingBox();
            final BoundingBox3i lastNetworkedBounds = serverSubLevel.lastNetworkedBoundingBox();

            if (!plotBounds.equals(lastNetworkedBounds)) {
                lastNetworkedBounds.set(plotBounds);

                final long l = getSubLevelLong(serverSubLevel, container);
                serverSubLevel.playerSink().sendPacket(new ClientboundChangeBoundsSubLevelPacket(l, plotBounds));
            }
        }
    }

    public int getInterpolationTick() {
        return this.interpolationTick;
    }

    /**
     * Sends updates regarading sub-level movement to all tracking players
     *
     * @param container the sublevels to send updates for
     */
    private void sendMovementUpdates(final SubLevelContainer container) {
        // we want to batch updates we send to players, so we'll collect them here
        final Map<UUID, List<SubLevelUpdateTicket>> movementUpdates = new Object2ObjectOpenHashMap<>();

        for (final SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            final ServerSubLevel serverSubLevel = (ServerSubLevel) subLevel;
            final Collection<UUID> tracking = serverSubLevel.getTrackingPlayers();
            SubLevelUpdateTicket.UpdateTicketType type = SubLevelUpdateTicket.UpdateTicketType.MOVE;

            if (!serverSubLevel.logicalPose().withinTolerance(serverSubLevel.lastNetworkedPose(), 0.015 / 16.0, Math.toRadians(0.015))) {
                serverSubLevel.lastNetworkedPose().set(serverSubLevel.logicalPose());
                serverSubLevel.setLastNetworkedStopped(false);
            } else {
                if (!serverSubLevel.getLastNetworkedStopped()) {
                    type = SubLevelUpdateTicket.UpdateTicketType.STOP;
                    serverSubLevel.setLastNetworkedStopped(true);
                } else {
                    continue;
                }
            }

            for (final UUID uuid : tracking) {
                final ServerPlayer player = (ServerPlayer) this.level.getPlayerByUUID(uuid);

                if (player == null) {
                    continue;
                }

                final List<SubLevelUpdateTicket> playerUpdates = movementUpdates.computeIfAbsent(uuid, (p) -> new ArrayList<>());
                playerUpdates.add(new SubLevelUpdateTicket(serverSubLevel, type));
            }
        }

        final long ms = System.currentTimeMillis();
        final int msSinceLastSend;
        if (this.lastSendMs == -1) {
            msSinceLastSend = (int) (1000.0 / this.level.getServer().tickRateManager().tickrate());
        } else {
            msSinceLastSend = (int) (ms - this.lastSendMs);
        }
        this.lastSendMs = ms;

        this.pluginNeededPlayers.clear();
        for (final SubLevelTrackingPlugin plugin : this.plugins) {
            for (final UUID neededPlayer : plugin.neededPlayers()) {
                this.pluginNeededPlayers.add(neededPlayer);
            }
        }

        this.currentlyUpdatingPlayers.addAll(movementUpdates.keySet());
        this.currentlyUpdatingPlayers.addAll(this.pluginNeededPlayers);

        final Iterator<UUID> currentlyUpdatingIter = this.currentlyUpdatingPlayers.iterator();
        while (currentlyUpdatingIter.hasNext()) {
            final UUID uuid = currentlyUpdatingIter.next();

            final ServerPlayer player = (ServerPlayer) this.level.getPlayerByUUID(uuid);
            if (player == null) {
                currentlyUpdatingIter.remove();
                continue;
            }

            if (!movementUpdates.containsKey(uuid)) {
                if (this.pluginNeededPlayers.contains(uuid)) {
                    player.connection.send(new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotInfoDualPacket(msSinceLastSend, this.interpolationTick, false)));
                    continue;
                }

                player.connection.send(new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotInfoDualPacket(msSinceLastSend, this.interpolationTick, true)));

                currentlyUpdatingIter.remove();
            }
        }

        for (final SubLevelTrackingPlugin plugin : this.plugins) {
            plugin.sendTrackingData(this.interpolationTick);
        }

        for (final Map.Entry<UUID, List<SubLevelUpdateTicket>> entry : movementUpdates.entrySet()) {
            final UUID uuid = entry.getKey();
            final ServerPlayer player = (ServerPlayer) this.level.getPlayerByUUID(uuid);

            final List<SubLevelUpdateTicket> toUpdate = entry.getValue();
            final List<ClientboundSableSnapshotDualPacket.Entry> entries = new ObjectArrayList<>();

            for (final SubLevelUpdateTicket ticket : toUpdate) {
                final ServerSubLevel serverSubLevel = (ServerSubLevel) ticket.subLevels;
                final long l = getSubLevelLong(serverSubLevel, container);

                switch (ticket.type) {
                    case STOP -> player.connection.send(new ClientboundCustomPayloadPacket(new ClientboundStopMovingSubLevelPacket(l)));
                    case MOVE -> {
                        final Vector3f linearVelocity = new Vector3f((float) serverSubLevel.latestLinearVelocity.x, (float) serverSubLevel.latestLinearVelocity.y, (float) serverSubLevel.latestLinearVelocity.z);
                        final Vector3f angularVelocity = new Vector3f((float) serverSubLevel.latestAngularVelocity.x, (float) serverSubLevel.latestAngularVelocity.y, (float) serverSubLevel.latestAngularVelocity.z);
                        entries.add(new ClientboundSableSnapshotDualPacket.Entry(l, serverSubLevel.logicalPose(), linearVelocity, angularVelocity));
                    }
                }
            }

            final int maxBatchSize = 16;

            final SableUDPServer udpServer = SableUDPServer.getServer(this.level.getServer());
            if (udpServer != null && udpServer.isConnectedTo(player)) {
                final Iterator<ClientboundSableSnapshotDualPacket.Entry> iter = entries.iterator();

                udpServer.sendUDPPacket(player, new ClientboundSableSnapshotInfoDualPacket(msSinceLastSend, this.interpolationTick, false), true);
                while (iter.hasNext()) {
                    final List<ClientboundSableSnapshotDualPacket.Entry> batch = new ObjectArrayList<>();

                    for (int i = 0; i < maxBatchSize && iter.hasNext(); i++) {
                        batch.add(iter.next());
                    }

                    udpServer.sendUDPPacket(player, new ClientboundSableSnapshotDualPacket(this.interpolationTick, batch), true);
                }
            } else {
                // We have to fallback to TCP, unfortunately...
                final Iterator<ClientboundSableSnapshotDualPacket.Entry> iter = entries.iterator();

                while (iter.hasNext()) {
                    final List<ClientboundSableSnapshotDualPacket.Entry> batch = new ObjectArrayList<>();

                    for (int i = 0; i < maxBatchSize && iter.hasNext(); i++) {
                        batch.add(iter.next());
                    }

                    player.connection.send(
                            new ClientboundBundlePacket(List.of(
                                    new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotInfoDualPacket(msSinceLastSend, this.interpolationTick, false)),
                                    new ClientboundCustomPayloadPacket(new ClientboundSableSnapshotDualPacket(this.interpolationTick, batch))
                            )));
                }
            }
        }

        this.interpolationTick++;
    }

    /**
     * Other mods or projects (looking at you, Simulated!) may want to piggyback off of the snapshot interpolation
     * system so that their content can also abide by it and benefit from its improvements. As such, we expose
     * "tracking" plugins for these projects to give us players that need to be informed about the interpolation tick
     * at any given moment.
     */
    public void addTrackingPlugin(final SubLevelTrackingPlugin plugin) {
        if (this.plugins.contains(plugin)) {
            return;
        }
        this.plugins.add(plugin);
    }

    private record SubLevelUpdateTicket(SubLevel subLevels, UpdateTicketType type) {
        private enum UpdateTicketType {
            STOP,
            MOVE
        }
    }
}
