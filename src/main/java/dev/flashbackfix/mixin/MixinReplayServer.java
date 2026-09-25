package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayGamePacketHandler;
import com.moulberry.flashback.playback.ReplayPlayer;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.TempFolderProvider;
import dev.flashbackfix.ext.ReplayGamePacketHandlerComplexSpawnExt;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import dev.flashbackfix.ext.ReplayServerComplexSpawnExt;
import dev.flashbackfix.ext.ReplayServerRegistryExt;
import dev.flashbackfix.compat.ReplayRegistryCompat;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
        ReplayServerRegistryExt {

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
            applicable.put(entry.getKey(), entry.getValue());
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
    private Map<Entity, double[]> flashbackNeoForgeFixed$authoritativeEntityPoses;
    @Unique
    private Set<Entity> flashbackNeoForgeFixed$authoritativePoseUpdates;
    @Unique
    private boolean flashbackNeoForgeFixed$snapshotDeliveryPending;

    /**
     * Every replay snapshot is a full replacement, not a delta. Discard the previous viewer
     * catch-up image before snapshot actions build the new one, and reset Sable's client-only plot
     * state. Without this, seeks accumulate old StartTracking/chunk packets and retain sub-levels
     * that are absent from the destination snapshot.
     */
    @Inject(method = "clearDataForPlayingSnapshot", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$beginCompleteSnapshotReplacement(CallbackInfo ci) {
        if (this.flashbackNeoForgeFixed$viewerCatchupBacklog != null) {
            this.flashbackNeoForgeFixed$viewerCatchupBacklog.clear();
        }
        if (this.flashbackNeoForgeFixed$viewerCatchupOffsets != null) {
            this.flashbackNeoForgeFixed$viewerCatchupOffsets.clear();
        }
        this.flashbackNeoForgeFixed$snapshotDeliveryPending = true;
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
            this.flashbackNeoForgeFixed$viewerCatchupBacklog = new CopyOnWriteArrayList<>();
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
                : List.copyOf(this.flashbackNeoForgeFixed$viewerCatchupBacklog);
        List<ServerPlayer> players = ((ReplayServer) (Object) this).getPlayerList().getPlayers();
        Map<ServerPlayer, Integer> offsets = this.flashbackNeoForgeFixed$catchupOffsets();
        offsets.keySet().removeIf(player -> !players.contains(player));
        boolean deliveredToReplayViewer = false;
        for (ServerPlayer player : players) {
            if (player instanceof ReplayPlayer) {
                int offset = Math.min(offsets.getOrDefault(player, 0), ready.size());
                for (int index = offset; index < ready.size(); index++) {
                    player.connection.send(ready.get(index));
                }
                offsets.put(player, ready.size());
                deliveredToReplayViewer = true;
            }
        }
        if (deliveredToReplayViewer) {
            // Packets added by fast-forward were sent after every StartTracking/chunk/finalize
            // packet from the replacement snapshot. Future live timeline packets may now use the
            // direct path until another snapshot/seek establishes a new ordering barrier.
            this.flashbackNeoForgeFixed$snapshotDeliveryPending = false;
        }
    }
}
