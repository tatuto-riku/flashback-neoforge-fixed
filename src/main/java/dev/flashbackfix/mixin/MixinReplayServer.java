package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayGamePacketHandler;
import com.moulberry.flashback.playback.ReplayPlayer;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.TempFolderProvider;
import dev.flashbackfix.ext.ReplayGamePacketHandlerComplexSpawnExt;
import dev.flashbackfix.ext.ReplayGamePacketHandlerPowerGridExt;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import dev.flashbackfix.ext.ReplayServerComplexSpawnExt;
import dev.flashbackfix.ext.ReplayServerPowerGridExt;
import dev.flashbackfix.ext.ReplayServerRegistryExt;
import dev.flashbackfix.compat.ReplayRegistryCompat;
import dev.flashbackfix.compat.ReplayChunkBlockEntityLoadScope;
import dev.flashbackfix.compat.SableCompat;
import dev.flashbackfix.compat.VoxyReplayCompat;
import dev.flashbackfix.FlashbackNeoForgeFixed;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import com.moulberry.flashback.ext.LevelChunkExt;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReplayServer.class)
public class MixinReplayServer implements ReplayServerComplexSpawnExt, ReplayServerCatchupExt,
        ReplayServerRegistryExt, ReplayServerPowerGridExt {

    @Unique
    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    @Shadow
    @Final
    private ReplayGamePacketHandler gamePacketHandler;

    @Shadow
    private StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$restoreLegacyRegistryOrder(CallbackInfo ci) {
        ReplayRegistryCompat.applyLegacyNamespaceOrder((ReplayServer) (Object) this);
    }

    @Override
    public void flashbackNeoForgeFixed$applyBuiltInRegistrySnapshot(
            Map<ResourceLocation, RegistrySnapshot> snapshots) {
        // NeoForge clears a registry before applying its snapshot. Preflight every entry so an old
        // recording made with a now-uninstalled mod cannot leave the live global registry half empty.
        Map<ResourceLocation, RegistrySnapshot> applicable = new LinkedHashMap<>();
        Map<ResourceLocation, RegistrySnapshot> current =
                RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
        for (var entry : snapshots.entrySet()) {
            boolean invalidIds = entry.getValue().getIds().int2ObjectEntrySet().stream()
                    .anyMatch(id -> id.getIntKey() < 0 || id.getValue() == null);
            if (invalidIds) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                        "Skipping structurally invalid replay registry {} (negative/null ID mapping)",
                        entry.getKey());
                continue;
            }

            Registry<?> registry = BuiltInRegistries.REGISTRY.get(entry.getKey());
            if (registry == null) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                        "Skipping unavailable replay registry {}", entry.getKey());
                continue;
            }

            List<ResourceLocation> unavailable = new ArrayList<>();
            for (ResourceLocation id : entry.getValue().getIds().values()) {
                if (!registry.containsKey(id)) {
                    unavailable.add(id);
                }
            }
            if (!unavailable.isEmpty()) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                        "Skipping replay registry {} because {} recorded entries are unavailable: {}",
                        entry.getKey(), unavailable.size(),
                        unavailable.subList(0, Math.min(8, unavailable.size())));
                continue;
            }
            if (ReplayRegistryCompat.hasSameMappings(current.get(entry.getKey()), entry.getValue())) {
                continue;
            }
            applicable.put(entry.getKey(), entry.getValue());
        }

        if (applicable.isEmpty()) {
            return;
        }

        var missing = ReplayRegistryCompat.applySnapshot(applicable);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Replay registry snapshot contains unavailable entries: " + missing);
        }
        ReplayServer replayServer = (ReplayServer) (Object) this;
        this.gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                .bind(RegistryFriendlyByteBuf.decorator(replayServer.registryAccess())).codec();
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$restoreLocalRegistryOrder(CallbackInfo ci) {
        RegistryManager.revertToFrozen();
    }

    @Redirect(method = "stopServer", at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/TempFolderProvider;deleteTemp(Lcom/moulberry/flashback/TempFolderProvider$TempFolderType;Ljava/util/UUID;)V"))
    private void flashbackNeoForgeFixed$waitForVoxyStorageClose(
            TempFolderProvider.TempFolderType type, java.util.UUID playbackId) {
        VoxyReplayCompat.deleteOrDefer(type, playbackId);
    }

    @Override
    public Entity flashbackNeoForgeFixed$applyComplexSpawnData(int entityId, byte[] data) {
        return ((ReplayGamePacketHandlerComplexSpawnExt) this.gamePacketHandler)
                .flashbackNeoForgeFixed$applyComplexSpawnData(entityId, data);
    }

    @Override
    public void flashbackNeoForgeFixed$applyPowerGridEntityData(
            int entityId, CompoundTag data) {
        ((ReplayGamePacketHandlerPowerGridExt) this.gamePacketHandler)
                .flashbackNeoForgeFixed$applyPowerGridEntityData(entityId, data);
    }

    // Actions embedded in the file's initial snapshot get processed on the replay server's very first
    // tick, before any client has had time to finish connecting - so forwarding them straight to
    // "whoever is watching right now" (ActionModdedPayload/ActionForwardedGamePacket's normal behaviour)
    // reaches nobody and the data is gone for good, even though it describes state that existed from the
    // start of the recording and every viewer needs it. Queuing it here and replaying it to each new
    // viewer as they show up in getPlayerList() fixes that without those actions needing to know or care
    // whether anyone happened to be connected when they were read.
    //
    // Left null instead of assigned inline: ReplayServer is spun up via MinecraftServer's own thread
    // factory, and Mixin's constructor-initializer merge doesn't reliably run for it, so an inline
    // initializer here was observed null on first use. Lazily creating on first access sidesteps that
    // entirely regardless of when/whether the merge happens.
    @Unique
    private List<Packet<?>> flashbackNeoForgeFixed$viewerCatchupBacklog;
    @Unique
    private Map<ServerPlayer, Integer> flashbackNeoForgeFixed$viewerCatchupOffsets;
    @Unique
    private Map<Long, LinkedHashMap<BlockPos, ClientboundBlockEntityDataPacket>>
            flashbackNeoForgeFixed$chunkBlockEntityUpdates;
    @Unique
    private boolean flashbackNeoForgeFixed$snapshotBlockEntityUpdatesReady;
    @Unique
    private Map<Entity, double[]> flashbackNeoForgeFixed$authoritativeEntityPoses;
    @Unique
    private Set<Entity> flashbackNeoForgeFixed$authoritativePoseUpdates;
    @Unique
    private boolean flashbackNeoForgeFixed$snapshotDeliveryPending;
    @Unique
    private int flashbackNeoForgeFixed$snapshotBacklogRetentionTicks;
    @Unique
    private static final int FLASHBACK_NEOFORGE_FIXED$MAX_CATCHUP_PACKETS_PER_TICK = 128;
    @Unique
    private static final long FLASHBACK_NEOFORGE_FIXED$CATCHUP_TIME_BUDGET_NANOS = 2_000_000L;

    // A replay snapshot is a complete replacement. Reapply its cached chunks even when the fake
    // server still carries the same cache id from an earlier seek: the physical replay client may
    // have unloaded that chunk or may be a replacement viewer which never received it.
    @Redirect(method = "handleLevelChunkCached", at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/playback/ReplayServer;doesCachedChunkIdMatch("
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;I)Z"))
    private boolean flashbackNeoForgeFixed$refreshSnapshotChunk(
            LevelChunk chunk, int cacheId) {
        if (((ReplayServer) (Object) this).isProcessingSnapshot) {
            return false;
        }
        return chunk instanceof LevelChunkExt extension
                && extension.flashback$getCachedChunkId() == cacheId;
    }

    // Flashback 0.39.x catches and silently discards every exception thrown while applying a cached
    // chunk. Preserve its fault isolation, but log the exact coordinates so a bad mod block entity
    // cannot masquerade as a random hole in the replay world.
    @Redirect(method = "handleLevelChunkCached", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;"
                    + "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V"))
    private void flashbackNeoForgeFixed$logCachedChunkApplicationFailure(
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet,
            ClientGamePacketListener listener) {
        ReplayChunkBlockEntityLoadScope.enter();
        try {
            packet.handle(listener);
        } catch (RuntimeException exception) {
            FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                    "Failed to apply replay chunk at {},{}",
                    packet.getX(), packet.getZ(), exception);
            throw exception;
        } finally {
            ReplayChunkBlockEntityLoadScope.exit();
        }
    }

    /**
     * Every replay snapshot is a full replacement, not a delta. Discard the previous viewer
     * catch-up image before snapshot actions build the new one, and reset Sable's client-only plot
     * state. Without this, seeks accumulate old StartTracking/chunk packets and retain sub-levels
     * that are absent from the destination snapshot.
     */
    @Inject(method = "clearDataForPlayingSnapshot", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$beginCompleteSnapshotReplacement(CallbackInfo ci) {
        ((ReplayGamePacketHandlerPowerGridExt) this.gamePacketHandler)
                .flashbackNeoForgeFixed$resetPowerGridEntityData();
        if (this.flashbackNeoForgeFixed$viewerCatchupBacklog != null) {
            this.flashbackNeoForgeFixed$viewerCatchupBacklog.clear();
        }
        if (this.flashbackNeoForgeFixed$viewerCatchupOffsets != null) {
            this.flashbackNeoForgeFixed$viewerCatchupOffsets.clear();
        }
        if (this.flashbackNeoForgeFixed$chunkBlockEntityUpdates != null) {
            this.flashbackNeoForgeFixed$chunkBlockEntityUpdates.clear();
        }
        this.flashbackNeoForgeFixed$snapshotBlockEntityUpdatesReady = false;
        this.flashbackNeoForgeFixed$snapshotDeliveryPending = true;
        this.flashbackNeoForgeFixed$snapshotBacklogRetentionTicks = 0;
        if (FlashbackNeoForgeFixed.isSableLoaded) {
            // Keep the reset marker in the same ordered backlog as the snapshot itself. Scheduling
            // a client task here can overtake one already-decoded movement packet and leave that old
            // pose alive after the reset, which becomes visible after several seeks.
            this.flashbackNeoForgeFixed$backlog().add(SableCompat.beginReplaySnapshotReset());
        }
    }

    @Unique
    private Map<Entity, double[]> flashbackNeoForgeFixed$authoritativePoses() {
        if (this.flashbackNeoForgeFixed$authoritativeEntityPoses == null) {
            this.flashbackNeoForgeFixed$authoritativeEntityPoses = new IdentityHashMap<>();
        }
        return this.flashbackNeoForgeFixed$authoritativeEntityPoses;
    }

    @Unique
    private Set<Entity> flashbackNeoForgeFixed$authoritativePoseUpdates() {
        if (this.flashbackNeoForgeFixed$authoritativePoseUpdates == null) {
            this.flashbackNeoForgeFixed$authoritativePoseUpdates =
                    Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return this.flashbackNeoForgeFixed$authoritativePoseUpdates;
    }

    // ActionMoveEntities is the recorded ground truth. Modded controller entities can simulate a
    // different result during super.tickServer(), so remember the result as it is decoded.
    @Redirect(method = "handleMoveEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;moveTo(DDDFF)V"))
    private void flashbackNeoForgeFixed$captureAuthoritativePose(
            Entity entity, double x, double y, double z, float yaw, float pitch) {
        entity.moveTo(x, y, z, yaw, pitch);
        this.flashbackNeoForgeFixed$authoritativePoses().put(entity,
                new double[] {x, y, z, yaw, pitch, entity.getYHeadRot(), entity.onGround() ? 1 : 0});
        this.flashbackNeoForgeFixed$authoritativePoseUpdates().add(entity);
    }

    // The remaining recorded pose fields are applied by handleMoveEntities after moveTo. Capture
    // them at method exit so replay-side physics cannot turn a flying/jumping entity into a grounded
    // one before its tracking packet is generated.
    @Inject(method = "handleMoveEntities", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$captureRemainingAuthoritativePose(
            RegistryFriendlyByteBuf buf, CallbackInfo ci) {
        if (this.flashbackNeoForgeFixed$authoritativePoseUpdates == null) {
            return;
        }
        for (Entity entity : this.flashbackNeoForgeFixed$authoritativePoseUpdates) {
            double[] pose = this.flashbackNeoForgeFixed$authoritativeEntityPoses.get(entity);
            if (pose != null) {
                pose[5] = entity.getYHeadRot();
                pose[6] = entity.onGround() ? 1 : 0;
            }
        }
        this.flashbackNeoForgeFixed$authoritativePoseUpdates.clear();
    }

    // Restore immediately after all server entities and block entities have ticked, but before
    // Flashback builds the forced teleport packets for needsPositionUpdate.
    @Inject(method = "runUpdates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;tickServer(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER))
    private void flashbackNeoForgeFixed$restoreRecordedPosesAfterSimulation(
            BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (this.flashbackNeoForgeFixed$authoritativeEntityPoses == null) {
            return;
        }
        Iterator<Map.Entry<Entity, double[]>> iterator =
                this.flashbackNeoForgeFixed$authoritativeEntityPoses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, double[]> entry = iterator.next();
            Entity entity = entry.getKey();
            if (entity.isRemoved() || entity.level().getServer() != (Object) this) {
                iterator.remove();
                continue;
            }
            if (!entity.isPassenger()) {
                double[] pose = entry.getValue();
                entity.moveTo(pose[0], pose[1], pose[2], (float) pose[3], (float) pose[4]);
                entity.setYHeadRot((float) pose[5]);
                entity.setOnGround(pose[6] != 0);
            }
        }
    }

    @Unique
    private List<Packet<?>> flashbackNeoForgeFixed$backlog() {
        if (this.flashbackNeoForgeFixed$viewerCatchupBacklog == null) {
            // Every producer and consumer runs on ReplayServer's server thread. CopyOnWriteArrayList
            // turns a large chunk snapshot into quadratic allocation by cloning its backing array for
            // every block-entity packet, which can exhaust even a 16 GiB heap in a dense modpack.
            this.flashbackNeoForgeFixed$viewerCatchupBacklog = new ArrayList<>();
        }
        return this.flashbackNeoForgeFixed$viewerCatchupBacklog;
    }

    @Unique
    private Map<ServerPlayer, Integer> flashbackNeoForgeFixed$catchupOffsets() {
        if (this.flashbackNeoForgeFixed$viewerCatchupOffsets == null) {
            this.flashbackNeoForgeFixed$viewerCatchupOffsets = new IdentityHashMap<>();
        }
        return this.flashbackNeoForgeFixed$viewerCatchupOffsets;
    }

    @Override
    public void flashbackNeoForgeFixed$queueForNewViewers(Packet<?> packet) {
        this.flashbackNeoForgeFixed$backlog().add(packet);
    }

    @Override
    public boolean flashbackNeoForgeFixed$deferUntilSnapshotDelivered(Packet<?> packet) {
        if (!this.flashbackNeoForgeFixed$snapshotDeliveryPending) {
            return false;
        }
        this.flashbackNeoForgeFixed$backlog().add(packet);
        return true;
    }

    @Override
    public void flashbackNeoForgeFixed$cacheChunkBlockEntityUpdate(
            ClientboundBlockEntityDataPacket packet) {
        if (this.flashbackNeoForgeFixed$chunkBlockEntityUpdates == null) {
            this.flashbackNeoForgeFixed$chunkBlockEntityUpdates = new LinkedHashMap<>();
        }
        BlockPos pos = packet.getPos();
        long chunkPos = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        this.flashbackNeoForgeFixed$chunkBlockEntityUpdates
                .computeIfAbsent(chunkPos, ignored -> new LinkedHashMap<>())
                .put(pos.immutable(), packet);
    }

    @Override
    public List<ClientboundBlockEntityDataPacket>
            flashbackNeoForgeFixed$getChunkBlockEntityUpdates(long chunkPos) {
        if (this.flashbackNeoForgeFixed$chunkBlockEntityUpdates == null) {
            return List.of();
        }
        Map<BlockPos, ClientboundBlockEntityDataPacket> updates =
                this.flashbackNeoForgeFixed$chunkBlockEntityUpdates.get(chunkPos);
        return updates == null || updates.isEmpty()
                ? List.of()
                : List.copyOf(updates.values());
    }

    @Override
    public void flashbackNeoForgeFixed$finishSnapshotBlockEntityUpdates() {
        this.flashbackNeoForgeFixed$snapshotBlockEntityUpdatesReady = true;
    }

    // ReplayServer is always an IntegratedServer, so watching a replay is always exactly one physical
    // client - but which ServerPlayer identity represents that client is not stable: it logs in under a
    // throwaway "Replay Viewer" profile while the UI is still coming up, and that can be replaced by a
    // second login under its real profile. The replacement can rebuild the client level and its entities,
    // so state sent only to the throwaway identity is lost. Keep a per-player cursor into the durable
    // backlog; every identity receives all state that predates it, while an existing identity only gets
    // newly queued entries.
    // Deliver at the end of the tick. Direct Sable entity state can refer to an ordinary replay
    // entity (a player riding a plot-grid seat is the common example), so the server's normal entity
    // tracker must get the chance to send those ordinary entity spawns first. Packets on the same
    // connection then retain the required add-entity -> passengers ordering.
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$catchUpNewViewers(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        List<Packet<?>> ready = this.flashbackNeoForgeFixed$viewerCatchupBacklog == null
                ? List.of()
                : this.flashbackNeoForgeFixed$viewerCatchupBacklog;
        List<ServerPlayer> players = ((ReplayServer) (Object) this).getPlayerList().getPlayers();
        Map<ServerPlayer, Integer> offsets = this.flashbackNeoForgeFixed$catchupOffsets();
        offsets.keySet().removeIf(player -> !players.contains(player));
        boolean foundReplayViewer = false;
        boolean allReplayViewersCaughtUp = true;
        int remainingPacketBudget = FLASHBACK_NEOFORGE_FIXED$MAX_CATCHUP_PACKETS_PER_TICK;
        long deadline = System.nanoTime() + FLASHBACK_NEOFORGE_FIXED$CATCHUP_TIME_BUDGET_NANOS;
        for (ServerPlayer player : players) {
            if (player instanceof ReplayPlayer) {
                foundReplayViewer = true;
                int offset = Math.min(offsets.getOrDefault(player, 0), ready.size());
                int index = offset;
                // A dense modded snapshot can contain several thousand block-entity and payload
                // packets. Sending the whole image in one server tick makes the render thread
                // receive the same burst at once and causes a visible multi-second hitch. Preserve
                // strict packet order while spreading that burst over several small tick budgets.
                while (index < ready.size()
                        && remainingPacketBudget > 0
                        && (index == offset || System.nanoTime() < deadline)) {
                    player.connection.send(ready.get(index));
                    index++;
                    remainingPacketBudget--;
                }
                offsets.put(player, index);
                if (index < ready.size()) {
                    allReplayViewersCaughtUp = false;
                }
            }
        }

        // Snapshot actions run before the physical viewer's chunk stream has settled. Publish the
        // final, deduplicated block-entity image once at tick tail, after ordinary chunk tracking.
        // This includes both Copycats materials and Create's recorded Speed; limiting this pass to
        // Copycats allowed a reconstructed chunk to leave kinetics stopped until the next seek.
        // Any chunks sent on a later tick are covered by the Connection ordering mixin as well.
        if (this.flashbackNeoForgeFixed$snapshotBlockEntityUpdatesReady && foundReplayViewer) {
            if (this.flashbackNeoForgeFixed$chunkBlockEntityUpdates != null) {
                for (Map<BlockPos, ClientboundBlockEntityDataPacket> chunk
                        : this.flashbackNeoForgeFixed$chunkBlockEntityUpdates.values()) {
                    for (ClientboundBlockEntityDataPacket packet : chunk.values()) {
                        for (ServerPlayer player : players) {
                            if (player instanceof ReplayPlayer) {
                                player.connection.send(packet);
                            }
                        }
                    }
                }
            }
            this.flashbackNeoForgeFixed$snapshotBlockEntityUpdatesReady = false;
        }
        if (foundReplayViewer && allReplayViewersCaughtUp) {
            // Packets added by fast-forward were sent after every StartTracking/chunk/finalize
            // packet from the replacement snapshot. Future live timeline packets may now use the
            // direct path until another snapshot/seek establishes a new ordering barrier.
            this.flashbackNeoForgeFixed$snapshotDeliveryPending = false;
        }

        // Retain the completed image briefly because Flashback may replace its temporary login
        // identity during startup. Once the same viewer has remained connected for five seconds,
        // the single local client is authoritative and keeping every copied block-entity NBT packet
        // only competes with chunk rendering and recipe viewers for heap space.
        if (!this.flashbackNeoForgeFixed$snapshotDeliveryPending
                && foundReplayViewer
                && !ready.isEmpty()
                && ++this.flashbackNeoForgeFixed$snapshotBacklogRetentionTicks >= 100) {
            this.flashbackNeoForgeFixed$viewerCatchupBacklog.clear();
            this.flashbackNeoForgeFixed$viewerCatchupOffsets.clear();
            this.flashbackNeoForgeFixed$snapshotBacklogRetentionTicks = 0;
        }
    }
}
