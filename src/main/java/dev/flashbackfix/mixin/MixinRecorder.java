package dev.flashbackfix.mixin;

import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.record.Recorder;
import dev.flashbackfix.FlashbackNeoForgeFixed;
import dev.flashbackfix.action.ActionForwardedGamePacket;
import dev.flashbackfix.action.ActionForwardedModdedPayload;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.flashbackfix.action.ActionRegistrySnapshot;
import dev.flashbackfix.compat.SableCompat;
import dev.flashbackfix.compat.BlockEntityPacketSnapshotCache;
import dev.flashbackfix.compat.CreateContraptionSnapshotCompat;
import dev.flashbackfix.compat.InboundPayloadCapture;
import dev.flashbackfix.compat.ModdedPayloadSnapshotCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records two kinds of things Flashback's normal packet recording can't handle, both written through
 * Flashback's action system (bypassing its packet queue) so they land in the replay file at all:
 * <p>
 * 1. Modded custom payloads (ActionModdedPayload) - Flashback's packet codec is built straight from
 * vanilla's GameProtocols, which only knows a hardcoded list of vanilla debug custom-payloads, so any
 * payload a mod registers itself silently fails to encode and is dropped
 * (AsyncReplaySaver#writeGamePackets swallows the failure on purpose). This is what makes complex
 * entities from Create, Simulated, or any other mod using NeoForge's own networking show up correctly
 * during playback.
 * <p>
 * 2. Ordinary game packets that need to be forwarded straight to the viewer instead of applied to the
 * fake replay server (ActionForwardedGamePacket) - for anything whose position is meaningless to that
 * fake world but still needs to reach the viewer, e.g. Sable's physics sub-levels, which store their
 * content in real chunks ~20 million blocks from any normal position. See SableCompat for the one
 * place that decides which packets need this treatment.
 * <p>
 * Both share the same three capture points: entities/state that already existed when a snapshot was
 * taken (rebuilt from scratch here, since nothing recorded them originally), a one-off announcement
 * packet that's only ever sent the first time a client observes something (also has to be synthesized
 * at snapshot time for the same reason), and payloads/packets arriving live during recording.
 */
@Mixin(Recorder.class)
public class MixinRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    @Unique
    private volatile Queue<Consumer<ReplayWriter>> flashbackNeoForgeFixed$liveWriteTasks;

    /** Entity ids whose authoritative replay copy lives in Sable's remote plot grid on the viewer. */
    @Unique
    private volatile Set<Integer> flashbackNeoForgeFixed$plotEntityIds;

    // Flashback intentionally exposes this empty method as a snapshot extension point. Append the
    // original last-known block-entity update packets after chunk data so mod client-only fields are
    // decoded through their real packet path when a snapshot is opened or rewound to.
    @Inject(method = "writeCustomSnapshot", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$appendExactBlockEntityState(
            Consumer<Packet<? super ClientGamePacketListener>> consumer, CallbackInfo ci) {
        BlockEntityPacketSnapshotCache.appendSnapshotPackets(Minecraft.getInstance().level, consumer);
    }

    // Remote living entities should be recorded at their network interpolation target, as Flashback
    // normally does. Locally controlled entities are different: movement-floor and vehicle mods can
    // adjust their real transform after the last server lerp target was installed. Recording that stale
    // target loses jumping, flight, and other local motion. Select the real transform by vanilla's own
    // ownership predicate rather than by player or mod type.
    @Redirect(method = "writeEntityPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;lerpTargetX()D"))
    private double flashbackNeoForgeFixed$recordControlledX(LivingEntity entity) {
        return entity.isControlledByLocalInstance() ? entity.getX() : entity.lerpTargetX();
    }

    @Redirect(method = "writeEntityPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;lerpTargetY()D"))
    private double flashbackNeoForgeFixed$recordControlledY(Entity entity) {
        return entity.isControlledByLocalInstance() ? entity.getY() : entity.lerpTargetY();
    }

    @Redirect(method = "writeEntityPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;lerpTargetZ()D"))
    private double flashbackNeoForgeFixed$recordControlledZ(Entity entity) {
        return entity.isControlledByLocalInstance() ? entity.getZ() : entity.lerpTargetZ();
    }

    @Redirect(method = "writeEntityPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;lerpTargetYRot()F"))
    private float flashbackNeoForgeFixed$recordControlledYaw(Entity entity) {
        return entity.isControlledByLocalInstance() ? entity.getYRot() : entity.lerpTargetYRot();
    }

    @Redirect(method = "writeEntityPositions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;lerpTargetXRot()F"))
    private float flashbackNeoForgeFixed$recordControlledPitch(Entity entity) {
        return entity.isControlledByLocalInstance() ? entity.getXRot() : entity.lerpTargetXRot();
    }

    // Recorder can be allocated through paths where Mixin field initializers are not merged into the
    // constructor (the replay server has the same constraint). Packet capture also runs concurrently
    // on Netty and render threads, so initialize both collections lazily under a small synchronized
    // section instead of assuming an inline initializer has run.
    @Unique
    private Queue<Consumer<ReplayWriter>> flashbackNeoForgeFixed$liveWriteTasks() {
        Queue<Consumer<ReplayWriter>> tasks = this.flashbackNeoForgeFixed$liveWriteTasks;
        if (tasks == null) {
            synchronized (this) {
                tasks = this.flashbackNeoForgeFixed$liveWriteTasks;
                if (tasks == null) {
                    tasks = new ConcurrentLinkedQueue<>();
                    this.flashbackNeoForgeFixed$liveWriteTasks = tasks;
                }
            }
        }
        return tasks;
    }

    @Unique
    private Set<Integer> flashbackNeoForgeFixed$plotEntityIds() {
        Set<Integer> entityIds = this.flashbackNeoForgeFixed$plotEntityIds;
        if (entityIds == null) {
            synchronized (this) {
                entityIds = this.flashbackNeoForgeFixed$plotEntityIds;
                if (entityIds == null) {
                    entityIds = ConcurrentHashMap.newKeySet();
                    this.flashbackNeoForgeFixed$plotEntityIds = entityIds;
                }
            }
        }
        return entityIds;
    }

    @Inject(method = "writeSnapshot", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$beginSableEntitySnapshot(boolean asActualSnapshot, CallbackInfo ci) {
        if (FlashbackNeoForgeFixed.isSableLoaded) {
            this.flashbackNeoForgeFixed$plotEntityIds().clear();
        }
    }

    // NeoForge synchronizes numeric IDs for built-in registries (blocks, entity types, data
    // serializers, etc.) with configuration custom payloads. Vanilla Flashback snapshots only the
    // datapack registries, so preserve NeoForge's already-remapped client mapping before any game
    // packet is written. Without it, raw block-state and entity IDs decode as unrelated mod entries.
    @Inject(method = "writeSnapshot", at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/io/AsyncReplaySaver;writeConfigurationPackets(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/List;)V",
            shift = At.Shift.AFTER))
    private void flashbackNeoForgeFixed$recordBuiltInRegistrySnapshot(
            boolean asActualSnapshot, CallbackInfo ci) {
        byte[] snapshot = ActionRegistrySnapshot.capture();
        this.flashbackNeoForgeFixed$submit((Recorder) (Object) this,
                List.of(writer -> ActionRegistrySnapshot.write(writer, snapshot)));
    }

    // Runs right after writeSnapshot hands the entity add-packets (and everything else in the
    // snapshot) off to be written, so anything this data refers to is guaranteed to already be in the
    // recorded stream - and still inside the snapshot's byte range, since it runs before
    // endSnapshot() is submitted.
    @Inject(method = "writeSnapshot", at = @At(value = "INVOKE",
            target = "Lcom/moulberry/flashback/io/AsyncReplaySaver;writeGamePackets(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/List;)V",
            ordinal = 1, shift = At.Shift.AFTER))
    private void flashbackNeoForgeFixed$recordComplexSpawnDataOnSnapshot(boolean asActualSnapshot, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        List<Consumer<ReplayWriter>> tasks = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || !(entity instanceof IEntityWithComplexSpawn)) {
                continue;
            }

            // Plot entities are created directly on the replay viewer below. Their NeoForge spawn
            // payload must follow them there instead of being applied to a non-existent fake-server
            // entity by ActionModdedPayload.
            if (FlashbackNeoForgeFixed.isSableLoaded && SableCompat.isPlotEntity(entity)) {
                continue;
            }

            try {
                CreateContraptionSnapshotCompat.prepareForComplexSpawnSnapshot(entity);
                AdvancedAddEntityPayload payload = new AdvancedAddEntityPayload(entity);
                ActionModdedPayload.EncodedPayload encoded =
                        ActionModdedPayload.encodeSynthetic(ConnectionProtocol.PLAY, payload);
                if (encoded != null) {
                    tasks.add(writer -> ActionModdedPayload.write(writer, encoded));
                }
            } catch (Exception e) {
                LOGGER.error("Failed to capture complex spawn data for entity {} ({})", entity.getId(), entity.getType(), e);
            }
        }

        if (FlashbackNeoForgeFixed.isSableLoaded) {
            try {
                SableCompat.collectSubLevelSnapshots(level, tasks,
                        this.flashbackNeoForgeFixed$plotEntityIds());
            } catch (Exception e) {
                LOGGER.error("Failed to capture Sable sub-level snapshots", e);
            }
        }

        // Preserve last-known modded client state that arrived before recording began. This runs
        // after normal entity/chunk snapshot submission so entity-addressed payloads resolve when
        // the snapshot is replayed.
        ModdedPayloadSnapshotCache.appendSnapshotTasks(tasks);

        flashbackNeoForgeFixed$submit((Recorder) (Object) this, tasks);
    }

    // Only redirect packets that need the special "send straight to the viewer" treatment; anything
    // else is left to the normal packet queue exactly as before.
    @Inject(method = "writePacketAsync", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$captureLiveModdedPayload(Packet<?> packet, ConnectionProtocol phase, CallbackInfo ci) {
        if (packet instanceof ClientboundCustomPayloadPacket customPayloadPacket) {
            CustomPacketPayload payload = customPayloadPacket.payload();
            if (NetworkRegistry.getCodec(payload.type().id(), phase, PacketFlow.CLIENTBOUND) != null) {
                // Use the immutable bytes copied by PacketDecoder. Re-encoding a received payload
                // can consume a lazy/one-shot buffer that its actual client handler still needs.
                ActionModdedPayload.EncodedPayload encoded =
                        InboundPayloadCapture.get(payload, phase);
                if (encoded == null) {
                    return;
                }
                if (FlashbackNeoForgeFixed.isSableLoaded
                        && payload instanceof AdvancedAddEntityPayload advancedPayload
                        && this.flashbackNeoForgeFixed$plotEntityIds().contains(advancedPayload.entityId())) {
                    this.flashbackNeoForgeFixed$liveWriteTasks().add(
                            writer -> ActionForwardedModdedPayload.write(writer, encoded));
                } else {
                    this.flashbackNeoForgeFixed$liveWriteTasks().add(
                            writer -> ActionModdedPayload.write(writer, encoded));
                }
                ci.cancel();
            }
            return;
        }

        if (FlashbackNeoForgeFixed.isSableLoaded && this.flashbackNeoForgeFixed$isDirectSablePacket(packet)) {
            @SuppressWarnings("unchecked")
            Packet<? super ClientGamePacketListener> gamePacket = (Packet<? super ClientGamePacketListener>) packet;
            this.flashbackNeoForgeFixed$liveWriteTasks().add(
                    writer -> ActionForwardedGamePacket.write(writer, gamePacket));
            ci.cancel();
        }
    }

    @Unique
    private boolean flashbackNeoForgeFixed$isDirectSablePacket(Packet<?> packet) {
        boolean plotBound = switch (packet) {
            case ClientboundLevelChunkWithLightPacket p -> SableCompat.isPlotChunk(p.getX(), p.getZ());
            case ClientboundBlockUpdatePacket p -> SableCompat.isPlotPos(p.getPos());
            case ClientboundBlockEntityDataPacket p -> SableCompat.isPlotPos(p.getPos());
            case ClientboundBlockEventPacket p -> SableCompat.isPlotPos(p.getPos());
            case ClientboundBlockDestructionPacket p -> SableCompat.isPlotPos(p.getPos());
            case ClientboundLevelEventPacket p -> SableCompat.isPlotPos(p.getPos());
            case ClientboundForgetLevelChunkPacket p -> SableCompat.isPlotChunk(p.pos().x, p.pos().z);
            case ClientboundLightUpdatePacket p -> SableCompat.isPlotChunk(p.getX(), p.getZ());
            case ClientboundSectionBlocksUpdatePacket p -> flashbackNeoForgeFixed$isPlotSectionUpdate(p);
            default -> false;
        };
        if (plotBound) {
            return true;
        }

        if (packet instanceof ClientboundAddEntityPacket addEntity) {
            if (SableCompat.isPlotPos(net.minecraft.core.BlockPos.containing(
                    addEntity.getX(), addEntity.getY(), addEntity.getZ()))) {
                this.flashbackNeoForgeFixed$plotEntityIds().add(addEntity.getId());
                return true;
            }
            return false;
        }
        if (packet instanceof ClientboundAddExperienceOrbPacket addOrb) {
            if (SableCompat.isPlotPos(net.minecraft.core.BlockPos.containing(
                    addOrb.getX(), addOrb.getY(), addOrb.getZ()))) {
                this.flashbackNeoForgeFixed$plotEntityIds().add(addOrb.getId());
                return true;
            }
            return false;
        }

        // Sable appends this marker to movement packets whose coordinates are sub-level-local.
        // Sending such a packet through Flashback's fake server strips that meaning on re-broadcast.
        // Keep Sable's interface type out of this always-applied mixin. Mixin preprocesses every
        // member reference while attaching Recorder, even when the runtime branch is guarded by
        // isSableLoaded; a pack containing only sable-companion would otherwise crash here because
        // the Sable interface itself is absent. The optional class reference lives in SableCompat,
        // which is loaded only after the presence guard succeeds.
        if (SableCompat.isActuallyInSubLevel(packet)) {
            return true;
        }

        // Unknown removals are already forwarded by Flashback's ReplayGamePacketHandler. Let this
        // packet keep that normal path, merely forgetting ids so a later vanilla id reuse is safe.
        if (packet instanceof ClientboundRemoveEntitiesPacket removeEntities) {
            removeEntities.getEntityIds().forEach(
                    (int id) -> this.flashbackNeoForgeFixed$plotEntityIds().remove(id));
            return false;
        }

        return this.flashbackNeoForgeFixed$referencesPlotEntity(packet);
    }

    @Unique
    private static boolean flashbackNeoForgeFixed$isPlotSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        boolean[] plotBound = {false};
        packet.runUpdates((pos, state) -> {
            if (!plotBound[0] && SableCompat.isPlotPos(pos)) {
                plotBound[0] = true;
            }
        });
        return plotBound[0];
    }

    @Unique
    private boolean flashbackNeoForgeFixed$referencesPlotEntity(Packet<?> packet) {
        ClientLevel level = Minecraft.getInstance().level;
        return switch (packet) {
            case ClientboundAnimatePacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getId());
            case ClientboundDamageEventPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.entityId())
                    || flashbackNeoForgeFixed$isPlotEntityId(p.sourceCauseId())
                    || flashbackNeoForgeFixed$isPlotEntityId(p.sourceDirectId());
            case ClientboundEntityEventPacket p -> flashbackNeoForgeFixed$isTrackedEntity(p.getEntity(level));
            case ClientboundHurtAnimationPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.id());
            case ClientboundMoveEntityPacket p -> flashbackNeoForgeFixed$isTrackedEntity(p.getEntity(level));
            case ClientboundProjectilePowerPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getId());
            case ClientboundRemoveMobEffectPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.entityId());
            case ClientboundRotateHeadPacket p -> flashbackNeoForgeFixed$isTrackedEntity(p.getEntity(level));
            case ClientboundSetCameraPacket p -> flashbackNeoForgeFixed$isTrackedEntity(p.getEntity(level));
            case ClientboundSetEntityDataPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.id());
            case ClientboundSetEntityLinkPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getSourceId())
                    || flashbackNeoForgeFixed$isPlotEntityId(p.getDestId());
            case ClientboundSetEntityMotionPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getId());
            case ClientboundSetEquipmentPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getEntity());
            case ClientboundSetPassengersPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getVehicle())
                    || java.util.Arrays.stream(p.getPassengers()).anyMatch(this::flashbackNeoForgeFixed$isPlotEntityId);
            case ClientboundSoundEntityPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getId());
            case ClientboundTakeItemEntityPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getItemId())
                    || flashbackNeoForgeFixed$isPlotEntityId(p.getPlayerId());
            case ClientboundTeleportEntityPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getId());
            case ClientboundUpdateAttributesPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getEntityId());
            case ClientboundUpdateMobEffectPacket p -> flashbackNeoForgeFixed$isPlotEntityId(p.getEntityId());
            default -> false;
        };
    }

    @Unique
    private boolean flashbackNeoForgeFixed$isTrackedEntity(Entity entity) {
        return entity != null && flashbackNeoForgeFixed$isPlotEntityId(entity.getId());
    }

    @Unique
    private boolean flashbackNeoForgeFixed$isPlotEntityId(int entityId) {
        return entityId != 0 && this.flashbackNeoForgeFixed$plotEntityIds().contains(entityId);
    }

    // Plot entities use the direct packet stream as their sole authoritative replay copy. Keeping a
    // second copy in Flashback's fake server cannot make it track at the remote plot coordinate, and
    // can produce conflicting passenger/state updates if a mod later changes its tracking behavior.
    @Redirect(method = "writeSnapshot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> flashbackNeoForgeFixed$excludePlotEntitiesFromSnapshot(ClientLevel level) {
        if (!FlashbackNeoForgeFixed.isSableLoaded) {
            return level.entitiesForRendering();
        }
        List<Entity> ordinaryEntities = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!SableCompat.isPlotEntity(entity)) {
                ordinaryEntities.add(entity);
            }
        }
        return ordinaryEntities;
    }

    // The ordinary half of a cross-boundary pair (most commonly a player) is still included in
    // Flashback's normal entity snapshot. Recorder would therefore emit its passengers packet even
    // though the plot vehicle itself was deliberately excluded from that stream. During playback
    // this necessarily reaches the client before the directly reconstructed vehicle and vanilla
    // drops it as "passengers for unknown entity". SableCompat writes the same relationship after
    // StartTracking -> chunks -> Finalize -> vehicle spawn, so suppress only this premature duplicate.
    @Redirect(method = "writeSnapshot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;isPassenger()Z"))
    private boolean flashbackNeoForgeFixed$excludePlotVehiclePassengerFromNormalSnapshot(Entity entity) {
        if (FlashbackNeoForgeFixed.isSableLoaded
                && entity.getVehicle() != null
                && SableCompat.isPlotEntity(entity.getVehicle())) {
            return false;
        }
        return entity.isPassenger();
    }

    // writeSnapshot builds its chunk snapshot straight from the client's currently loaded ClientChunkCache,
    // completely bypassing writePacketAsync - so the isPlotBoundPacket filtering above never sees these chunks.
    // Left alone, a Sable plot chunk ends up as ordinary persistent world data in the fake replay server, and
    // Sable (which has no idea it's inside a replay) reconstructs its own sub-level there and starts ticking
    // real physics on it, fighting the actual recorded motion this mixin is trying to deliver. Returning null
    // here makes the chunk invisible to the snapshot exactly like it was never loaded, same as the null check
    // writeChunkDataSnapshot already does for unloaded slots.
    @Redirect(method = "writeChunkDataSnapshot", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/atomic/AtomicReferenceArray;get(I)Ljava/lang/Object;"))
    private Object flashbackNeoForgeFixed$excludePlotChunksFromSnapshot(AtomicReferenceArray<LevelChunk> chunksList, int index) {
        LevelChunk chunk = chunksList.get(index);
        if (chunk != null && FlashbackNeoForgeFixed.isSableLoaded && SableCompat.isPlotChunk(chunk.getPos().x, chunk.getPos().z)) {
            return null;
        }
        return chunk;
    }

    @Inject(method = "flushPackets", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$flushLiveWriteTasks(CallbackInfoReturnable<Boolean> cir) {
        Queue<Consumer<ReplayWriter>> liveWriteTasks = this.flashbackNeoForgeFixed$liveWriteTasks();
        if (liveWriteTasks.isEmpty()) {
            return;
        }

        List<Consumer<ReplayWriter>> tasks = new ArrayList<>();
        Consumer<ReplayWriter> task;
        while ((task = liveWriteTasks.poll()) != null) {
            tasks.add(task);
        }
        flashbackNeoForgeFixed$submit((Recorder) (Object) this, tasks);
    }

    @Unique
    private static void flashbackNeoForgeFixed$submit(Recorder recorder, List<Consumer<ReplayWriter>> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        recorder.submitCustomTask(writer -> tasks.forEach(task -> task.accept(writer)));
    }
}
