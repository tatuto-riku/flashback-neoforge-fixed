package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayGamePacketHandler;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.CreateContraptionSnapshotCompat;
import dev.flashbackfix.compat.ReplayComplexSpawnPairing;
import dev.flashbackfix.ext.ReplayGamePacketHandlerComplexSpawnExt;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies complex spawn data recorded by MixinRecorder back onto the entity it belongs to. Reuses
 * getEntityOrPending so this works whether the entity has already been flushed into the level or is
 * still sitting in the pending-entities map from the same snapshot/tick batch.
 */
@Mixin(ReplayGamePacketHandler.class)
public class MixinReplayGamePacketHandler implements ReplayGamePacketHandlerComplexSpawnExt {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final Set<String> LOGGED_ENTITY_DATA_SCHEMA_MISMATCHES =
            ConcurrentHashMap.newKeySet();

    @Shadow
    @Final
    private ReplayServer replayServer;

    @Shadow
    private Entity getEntityOrPending(int entityId) {
        throw new UnsupportedOperationException();
    }

    @Shadow
    private void forward(Entity entity, Packet<?> packet) {
        throw new UnsupportedOperationException();
    }

    /**
     * A replay can outlive the exact mod/schema set with which it was recorded. Entity metadata has
     * only a numeric field id, so applying an entry whose serializer no longer matches that id would
     * otherwise terminate the whole replay server. Preserve every compatible entry and omit only
     * entries that cannot possibly be assigned to the reconstructed entity.
     */
    @Inject(method = "handleSetEntityData", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$validateEntityDataSchema(
            ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        Entity entity = this.getEntityOrPending(packet.id());
        if (entity == null) {
            return;
        }

        SynchedEntityData.DataItem<?>[] items =
                ((AccessorSynchedEntityData) (Object) entity.getEntityData())
                        .flashbackNeoForgeFixed$getItemsById();
        List<SynchedEntityData.DataValue<?>> compatible = new ArrayList<>(packet.packedItems().size());
        boolean rejectedAny = false;
        for (SynchedEntityData.DataValue<?> incoming : packet.packedItems()) {
            int id = incoming.id();
            SynchedEntityData.DataItem<?> current = id >= 0 && id < items.length ? items[id] : null;
            if (current != null
                    && Objects.equals(incoming.serializer(), current.getAccessor().serializer())) {
                compatible.add(incoming);
                continue;
            }

            rejectedAny = true;
            int expectedSerializer = current == null ? -1
                    : EntityDataSerializers.getSerializedId(current.getAccessor().serializer());
            int incomingSerializer = EntityDataSerializers.getSerializedId(incoming.serializer());
            String mismatch = entity.getType() + ":" + id + ":"
                    + expectedSerializer + ":" + incomingSerializer;
            if (LOGGED_ENTITY_DATA_SCHEMA_MISMATCHES.add(mismatch)) {
                LOGGER.warn("Ignoring incompatible replay entity data field {} for {} (entity {}): "
                                + "expected serializer {}, recorded serializer {}",
                        id, entity.getType(), entity.getId(), expectedSerializer, incomingSerializer);
            }
        }

        if (!rejectedAny) {
            return;
        }

        if (!compatible.isEmpty()) {
            ClientboundSetEntityDataPacket sanitized =
                    new ClientboundSetEntityDataPacket(packet.id(), compatible);
            this.forward(entity, sanitized);
            entity.getEntityData().assignValues(compatible);
        }
        ci.cancel();
    }

    /**
     * Send the recorded client update directly to viewers without decoding it in the replay server.
     *
     * <p>A {@link ClientboundBlockEntityDataPacket} contains a client update tag, not necessarily a
     * server-persistence tag. Some modded block entities select their decoding path from that fact and
     * perform client-only work while loading it. Applying such a tag to Flashback's server-side mirror
     * can therefore crash (or partially mutate it) before the packet ever reaches the real client.
     * The recorded packet is already the authoritative client state, so keep it opaque on the replay
     * server and deliver the exact packet instead.</p>
     */
    @Inject(method = "handleBlockEntityData", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$preserveExactBlockEntityClientPacket(
            ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        var viewers = this.replayServer.getReplayViewers();
        if (this.replayServer.isProcessingSnapshot || viewers.isEmpty()) {
            ((ReplayServerCatchupExt) this.replayServer)
                    .flashbackNeoForgeFixed$queueForNewViewers(packet);
        } else {
            for (ServerPlayer viewer : viewers) {
                viewer.connection.send(packet);
            }
        }
        ci.cancel();
    }

    // Chunk snapshots already contain each client block entity's exact update tag. Flashback loads
    // those tags into a server chunk, which changes their decoding path. Re-emit the raw tags after
    // normal chunk tracking so old recordings also recover client-only interpolation state on seek.
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$preserveChunkBlockEntityClientTags(
            ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()).accept(
                (pos, type, tag) -> {
                    if (tag == null) {
                        return;
                    }
                    ClientboundBlockEntityDataPacket exact =
                            InvokerClientboundBlockEntityDataPacket.flashbackNeoForgeFixed$create(
                                    pos.immutable(), type, tag.copy());
                    ((ReplayServerCatchupExt) this.replayServer)
                            .flashbackNeoForgeFixed$queueForNewViewers(exact);
                });
    }

    @Override
    public Entity flashbackNeoForgeFixed$applyComplexSpawnData(int entityId, byte[] data) {
        Entity entity = this.getEntityOrPending(entityId);
        if (!(entity instanceof IEntityWithComplexSpawn complexSpawn)) {
            LOGGER.warn("No IEntityWithComplexSpawn entity {} found for recorded complex spawn data (got {})", entityId, entity);
            return null;
        }

        // readSpawnData is not transactional. Create, for example, assigns several fields before it
        // discovers that the recorded contraption type is unavailable. Retrying pairing after that
        // failure serializes the half-initialized entity and disconnects the replay viewer. Decode
        // into an untracked entity of the same runtime type first, then prove that the resulting
        // state can also be written before touching Flashback's real pending/tracked entity.
        Entity probe = entity.getType().create(entity.level());
        if (!(probe instanceof IEntityWithComplexSpawn probeComplexSpawn)) {
            LOGGER.warn("Could not create validation entity for recorded complex spawn data {} ({})",
                    entityId, entity.getType());
            return null;
        }

        try {
            flashbackNeoForgeFixed$readComplexSpawnData(probe, probeComplexSpawn, data);

            RegistryFriendlyByteBuf validation = new RegistryFriendlyByteBuf(
                    Unpooled.buffer(), probe.registryAccess());
            try {
                probeComplexSpawn.writeSpawnData(validation);
            } finally {
                validation.release();
            }

            flashbackNeoForgeFixed$readComplexSpawnData(entity, complexSpawn, data);
            ReplayComplexSpawnPairing.clearInvalid(entity);
            return entity;
        } catch (Exception e) {
            ReplayComplexSpawnPairing.blockInvalid(entity);
            LOGGER.error("Rejected invalid or incompatible complex spawn data for entity {} before replay pairing",
                    entity, e);
            return null;
        }
    }

    private static void flashbackNeoForgeFixed$readComplexSpawnData(
            Entity entity, IEntityWithComplexSpawn complexSpawn, byte[] data) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(data), entity.registryAccess());
        try {
            complexSpawn.readSpawnData(buf);
            CreateContraptionSnapshotCompat.prepareForComplexSpawnSnapshot(entity);
        } finally {
            buf.release();
        }
    }
}
