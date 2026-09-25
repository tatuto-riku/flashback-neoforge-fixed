package dev.flashbackfix.compat;

import com.mojang.datafixers.util.Pair;
import com.moulberry.flashback.PacketHelper;
import com.moulberry.flashback.io.ReplayWriter;
import dev.flashbackfix.action.ActionForwardedGamePacket;
import dev.flashbackfix.action.ActionForwardedModdedPayload;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.flashbackfix.ext.SableInterpolationStateExt;
import dev.flashbackfix.mixin.InvokerSableSnapshotDualPacket;
import dev.ryanhcode.sable.SableClient;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.packet_mixin.PacketActuallyInSubLevelExtension;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ClientLevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sable stores the content of its physics "sub-levels" in real chunks, but at a plot-grid coordinate
 * roughly 20 million blocks from any normal position, rendered in place via its own pose transform.
 * That distance is exactly the problem: Flashback's snapshot only captures chunks near the player
 * (Recorder's own chunk cache), and even a live-captured chunk/block/entity packet at a plot position
 * would normally get applied to the fake replay server's reconstructed world - where nothing is ever
 * "near" a coordinate 20 million blocks out, so it's never re-synced to the viewer. Both gaps need the
 * same answer: recognize plot positions and route their packets through ActionForwardedGamePacket
 * instead, which sends them to the viewer directly. Kept in its own class, only ever called after
 * confirming Sable is loaded (FlashbackNeoForgeFixed.isSableLoaded), so these classes are never
 * touched otherwise.
 */
public final class SableCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    /** ReplayServer and the physical replay client share a JVM but run on different threads. */
    private static final AtomicLong REPLAY_SNAPSHOT_GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<Integer, Long> REPLAY_SNAPSHOT_BARRIERS =
            new ConcurrentHashMap<>();
    private static final AtomicLong RECEIVED_REPLAY_SNAPSHOT_GENERATION = new AtomicLong();
    private static final Deque<DeferredReplayMovement> DEFERRED_REPLAY_MOVEMENTS = new ArrayDeque<>();
    private static volatile long clearedReplaySnapshotGeneration;

    private record DeferredReplayMovement(
            long generation,
            ClientboundSableSnapshotDualPacket packet,
            PacketReceiveMode receiveMode) {
    }

    private SableCompat() {
    }

    /**
     * Reads Sable's local-coordinate packet marker without leaking its optional interface into an
     * always-applied mixin. Callers must first confirm that Sable itself is loaded.
     */
    public static boolean isActuallyInSubLevel(Packet<?> packet) {
        return packet instanceof PacketActuallyInSubLevelExtension extension
                && extension.sable$isActuallyInSubLevel();
    }

    public static boolean isPlotChunk(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(chunkX, chunkZ);
    }

    public static boolean isPlotPos(BlockPos pos) {
        return isPlotChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * True only for entities actually stored in Sable's remote plot grid. An ordinary player standing
     * on a sub-level remains a normal-world entity and must continue to be reconstructed by Flashback;
     * the plot seat/vehicle it rides is the direct entity, and the passenger packet joins the two.
     */
    public static boolean isPlotEntity(Entity entity) {
        return entity != null && !entity.isRemoved() && isPlotPos(entity.blockPosition());
    }

    /**
     * Begins a complete Sable client-state replacement for a replay snapshot.
     *
     * <p>A snapshot describes all sub-levels that exist at that point in time. Merely replacing a
     * sub-level when another start packet uses the same plot leaves every sub-level absent from the
     * new snapshot alive as a ghost. The returned vanilla packet is inserted at the front of the
     * snapshot backlog so the client resets exactly at the boundary in its network stream, including
     * snapshots that contain no sub-levels.
     */
    public static ClientboundPingPacket beginReplaySnapshotReset() {
        long generation = REPLAY_SNAPSHOT_GENERATION.incrementAndGet();
        int barrierId = 0xFB000000 | ((int) generation & 0x00FFFFFF);
        REPLAY_SNAPSHOT_BARRIERS.put(barrierId, generation);

        // A replay session cannot realistically seek through this many snapshots while a packet is
        // still in flight. Keeping a small history allows the same barrier to be delivered to a
        // replacement ReplayPlayer identity without growing this process-wide map forever.
        long oldestUsefulGeneration = generation - 64;
        REPLAY_SNAPSHOT_BARRIERS.entrySet().removeIf(
                entry -> entry.getValue() < oldestUsefulGeneration);
        return new ClientboundPingPacket(barrierId);
    }

    /**
     * Handles the marker inserted immediately before a complete replay snapshot. Because this runs
     * from the vanilla packet handler, it is ordered with every old and new replay packet on the
     * same client connection: old movement -> reset -> replacement StartTracking/chunks/movement.
     */
    public static boolean handleReplaySnapshotBarrier(int barrierId) {
        if (!(Minecraft.getInstance().getSingleplayerServer()
                instanceof com.moulberry.flashback.playback.ReplayServer)) {
            return false;
        }
        Long generation = REPLAY_SNAPSHOT_BARRIERS.get(barrierId);
        if (generation == null) {
            return false;
        }
        RECEIVED_REPLAY_SNAPSHOT_GENERATION.accumulateAndGet(generation, Math::max);
        clearReplaySnapshotState(generation);
        return true;
    }

    /** Retries the most recently received barrier after the client level/container becomes ready. */
    public static void ensureReceivedReplaySnapshotReset() {
        clearReplaySnapshotState(RECEIVED_REPLAY_SNAPSHOT_GENERATION.get());
    }

    public static long receivedReplaySnapshotGeneration() {
        return RECEIVED_REPLAY_SNAPSHOT_GENERATION.get();
    }

    /**
     * Sable sends StartTracking over TCP and movement over a separate UDP stream while recording.
     * Their capture order is therefore not a dependency order: the first movement for a newly
     * created sub-level can be stored immediately before its StartTracking packet. Sable normally
     * drops such a movement, losing the initial velocity and corrupting later interpolation.
     */
    public static boolean deferReplayMovementUntilTracked(
            ClientboundSableSnapshotDualPacket packet, Level level, PacketReceiveMode receiveMode) {
        if (!(Minecraft.getInstance().getSingleplayerServer()
                instanceof com.moulberry.flashback.playback.ReplayServer)) {
            return false;
        }

        // Replay data is deliberately delivered through the registered TCP payload codec. Any UDP
        // packet during playback was synthesized by the local ReplayServer rather than recorded.
        if (receiveMode == PacketReceiveMode.UDP) {
            LOGGER.debug("Dropping synthetic UDP Sable movement during replay");
            return true;
        }

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || allMovementPlotsTracked(packet, container)) {
            return false;
        }

        long generation = RECEIVED_REPLAY_SNAPSHOT_GENERATION.get();
        synchronized (DEFERRED_REPLAY_MOVEMENTS) {
            DEFERRED_REPLAY_MOVEMENTS.addLast(
                    new DeferredReplayMovement(generation, packet, receiveMode));
            while (DEFERRED_REPLAY_MOVEMENTS.size() > 256) {
                DeferredReplayMovement dropped = DEFERRED_REPLAY_MOVEMENTS.removeFirst();
                LOGGER.warn("Dropping stale deferred Sable replay movement from generation {} after queue overflow",
                        dropped.generation());
            }
        }

        List<String> missing = packet.entries().stream()
                .filter(entry -> container.getSubLevel(
                        ChunkPos.getX(entry.plotCoordinate()), ChunkPos.getZ(entry.plotCoordinate())) == null)
                .map(entry -> "(" + ChunkPos.getX(entry.plotCoordinate()) + ", "
                        + ChunkPos.getZ(entry.plotCoordinate()) + ")")
                .toList();
        LOGGER.debug("Deferred Sable replay movement generation={} tickPlots={} until StartTracking for {}",
                generation, packet.entries().size(), missing);
        return true;
    }

    /** Applies movements whose StartTracking packet has now allocated every referenced plot. */
    public static void flushDeferredReplayMovements(Level level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        long generation = RECEIVED_REPLAY_SNAPSHOT_GENERATION.get();
        List<DeferredReplayMovement> ready = new ArrayList<>();
        synchronized (DEFERRED_REPLAY_MOVEMENTS) {
            var iterator = DEFERRED_REPLAY_MOVEMENTS.iterator();
            while (iterator.hasNext()) {
                DeferredReplayMovement deferred = iterator.next();
                if (deferred.generation() < generation) {
                    iterator.remove();
                } else if (deferred.generation() == generation
                        && allMovementPlotsTracked(deferred.packet(), container)) {
                    iterator.remove();
                    ready.add(deferred);
                }
            }
        }

        for (DeferredReplayMovement deferred : ready) {
            LOGGER.debug("Applying deferred Sable replay movement generation={} after StartTracking",
                    deferred.generation());
            ((InvokerSableSnapshotDualPacket) (Object) deferred.packet())
                    .flashbackNeoForgeFixed$handleClient(level, deferred.receiveMode());
        }
    }

    private static boolean allMovementPlotsTracked(
            ClientboundSableSnapshotDualPacket packet, SubLevelContainer container) {
        for (ClientboundSableSnapshotDualPacket.Entry entry : packet.entries()) {
            if (container.getSubLevel(
                    ChunkPos.getX(entry.plotCoordinate()), ChunkPos.getZ(entry.plotCoordinate())) == null) {
                return false;
            }
        }
        return true;
    }

    private static void clearReplaySnapshotState(long generation) {
        if (generation == 0 || generation <= clearedReplaySnapshotGeneration) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getSingleplayerServer() instanceof com.moulberry.flashback.playback.ReplayServer)) {
            return;
        }

        // Local integrated-server Sable traffic bypasses Minecraft's Connection and waits in its
        // own client event loop. It therefore is not ordered by the replay barrier. Discard anything
        // generated before this snapshot; recorded replay movement is delivered through TCP and is
        // not stored in this queue.
        if (SableClient.NETWORK_EVENT_LOOP != null) {
            SableClient.NETWORK_EVENT_LOOP.clear();
        }
        synchronized (DEFERRED_REPLAY_MOVEMENTS) {
            DEFERRED_REPLAY_MOVEMENTS.clear();
        }

        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        // Direct plot entities never exist in Flashback's reconstructed server, so its ordinary
        // snapshot reset cannot send removals for them. Remove them while the old plot map still
        // exists, before unloading the chunks that identify those entities as plot-bound.
        List<Integer> plotEntityIds = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (isPlotEntity(entity)) {
                plotEntityIds.add(entity.getId());
            }
        }
        for (int entityId : plotEntityIds) {
            level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
        }

        ((SableInterpolationStateExt) container.getInterpolation())
                .flashbackNeoForgeFixed$resetForReplaySnapshot();

        // Copy before removal because getAllSubLevels exposes Sable's live backing list.
        List<ClientSubLevel> staleSubLevels = List.copyOf(container.getAllSubLevels());
        for (ClientSubLevel subLevel : staleSubLevels) {
            if (!subLevel.isRemoved()) {
                container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            }
        }

        clearedReplaySnapshotGeneration = generation;
        LOGGER.debug("Applied Sable replay snapshot barrier generation {}; removed {} stale sub-level(s)",
                generation, staleSubLevels.size());
    }

    public static void collectSubLevelSnapshots(ClientLevel level, List<Consumer<ReplayWriter>> tasks,
            Set<Integer> plotEntityIds) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        // Movement snapshots use SubLevelTrackingSystem's interpolationTick, not the world's saved
        // game time. Seed the synthesized tracking packet from the client's current interpolation
        // pointer so its two initial poses sort before the live movement snapshots captured next.
        int initialInterpolationTick = (int) Math.floor(container.getInterpolation().mostRecentInterpolationTick);

        for (ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }

            ClientLevelPlot plot = subLevel.getPlot();
            var origin = container.getOrigin();
            int localPlotX = plot.plotPos.x - origin.x;
            int localPlotZ = plot.plotPos.z - origin.y;
            long plotCoordinate = ChunkPos.asLong(localPlotX, localPlotZ);

            // Sable's tracking protocol identifies a plot by its index relative to the plot-grid
            // origin. plot.plotPos is the absolute plot-grid chunk coordinate (normally around
            // 10000,10000), and sending that value directly makes allocateSubLevel reject it as out
            // of bounds. This mirrors SubLevelTrackingSystem#getSubLevelLong exactly.
            ClientboundStartTrackingSubLevelPacket startTracking = new ClientboundStartTrackingSubLevelPacket(
                    plotCoordinate,
                    subLevel.getUniqueId(),
                    subLevel.lastPose(),
                    new Pose3d(subLevel.logicalPose()),
                    plot.getBoundingBox(),
                    subLevel.getName(),
                    initialInterpolationTick);
            addModdedPayload(tasks, startTracking, false);

            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }
                var chunkPacket = ClientChunkSnapshotCompat.capture(() ->
                        new ClientboundLevelChunkWithLightPacket(
                                chunk, plot.getLightEngine(), null, null));
                tasks.add(writer -> ActionForwardedGamePacket.write(writer, chunkPacket));
            }

            // This is not merely an end marker. Sable sets ClientSubLevel#finalized here and rebuilds
            // its render data after all plot chunks have arrived. Without it, a sub-level synthesized
            // into Flashback's initial snapshot owns the right chunks and pose but remains invisible.
            // Live assembly already records Sable's real finalize payload through MixinRecorder, which
            // is why only sub-levels that predate recording were affected.
            ClientboundFinalizeSubLevelPacket finalizeSubLevel =
                    new ClientboundFinalizeSubLevelPacket(plotCoordinate);
            addModdedPayload(tasks, finalizeSubLevel, false);

        }

        collectPlotEntitySnapshots(level, tasks, plotEntityIds);
    }

    /**
     * Flashback's normal entity snapshot is replayed into its fake server. That server intentionally
     * has none of Sable's remote plot chunks, so it cannot track a plot entity to the viewer. Rebuild
     * those entities directly after their sub-levels/chunks have been initialized. This mirrors every
     * piece of entity state Recorder writes for ordinary entities rather than special-casing seats.
     */
    private static void collectPlotEntitySnapshots(ClientLevel level, List<Consumer<ReplayWriter>> tasks,
            Set<Integer> plotEntityIds) {
        Set<Integer> writtenPassengerVehicles = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!isPlotEntity(entity) || PacketHelper.shouldIgnoreEntity(entity)) {
                continue;
            }

            plotEntityIds.add(entity.getId());
            addGamePacket(tasks, PacketHelper.createAddEntity(entity));

            List<SynchedEntityData.DataValue<?>> entityData = entity.getEntityData().getNonDefaultValues();
            if (entityData != null && !entityData.isEmpty()) {
                addGamePacket(tasks, new ClientboundSetEntityDataPacket(entity.getId(), entityData));
            }

            if (entity instanceof LivingEntity livingEntity) {
                Collection<AttributeInstance> attributes = livingEntity.getAttributes().getSyncableAttributes();
                if (!attributes.isEmpty()) {
                    addGamePacket(tasks, new ClientboundUpdateAttributesPacket(entity.getId(), attributes));
                }

                List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack stack = livingEntity.getItemBySlot(slot);
                    if (!stack.isEmpty()) {
                        equipment.add(Pair.of(slot, stack.copy()));
                    }
                }
                if (!equipment.isEmpty()) {
                    addGamePacket(tasks, new ClientboundSetEquipmentPacket(entity.getId(), equipment));
                }
            }

            if (entity.isVehicle() && writtenPassengerVehicles.add(entity.getId())) {
                addGamePacket(tasks, new ClientboundSetPassengersPacket(entity));
            }
            if (entity.isPassenger() && writtenPassengerVehicles.add(entity.getVehicle().getId())) {
                addGamePacket(tasks, new ClientboundSetPassengersPacket(entity.getVehicle()));
            }

            if (entity instanceof Leashable leashable && leashable.isLeashed()) {
                addGamePacket(tasks, new ClientboundSetEntityLinkPacket(entity, leashable.getLeashHolder()));
            }

            if (entity instanceof IEntityWithComplexSpawn) {
                try {
                    CreateContraptionSnapshotCompat.prepareForComplexSpawnSnapshot(entity);
                    AdvancedAddEntityPayload payload = new AdvancedAddEntityPayload(entity);
                    addModdedPayload(tasks, payload, true);
                } catch (Exception e) {
                    LOGGER.error("Failed to capture direct complex spawn data for Sable plot entity {} ({})",
                            entity.getId(), entity.getType(), e);
                }
            }
        }

    }

    @SuppressWarnings("unchecked")
    private static void addGamePacket(List<Consumer<ReplayWriter>> tasks,
            Packet<? super ClientGamePacketListener> packet) {
        tasks.add(writer -> ActionForwardedGamePacket.write(writer, packet));
    }

    private static void addModdedPayload(List<Consumer<ReplayWriter>> tasks,
            CustomPacketPayload payload, boolean forwarded) {
        ActionModdedPayload.EncodedPayload encoded =
                ActionModdedPayload.encodeSynthetic(ConnectionProtocol.PLAY, payload);
        if (encoded == null) {
            return;
        }
        if (forwarded) {
            tasks.add(writer -> ActionForwardedModdedPayload.write(writer, encoded));
        } else {
            tasks.add(writer -> ActionModdedPayload.write(writer, encoded));
        }
    }
}
