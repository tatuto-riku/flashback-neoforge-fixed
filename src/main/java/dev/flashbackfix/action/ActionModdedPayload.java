package dev.flashbackfix.action;

import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.ReplayComplexSpawnPairing;
import dev.flashbackfix.compat.ReplayPayloadPolicy;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import dev.flashbackfix.ext.ReplayServerComplexSpawnExt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Carries an arbitrary NeoForge custom payload through a replay file. Flashback's normal packet recording
 * can't do this on its own: its file codec is built straight from vanilla's GameProtocols, which only
 * knows how to encode a hardcoded list of vanilla debug custom-payloads, so any modded
 * ClientboundCustomPayloadPacket silently fails to encode and gets dropped (see
 * AsyncReplaySaver#writeGamePackets). Rather than reimplement that per mod, this uses
 * NetworkRegistry.getCodec - the same lookup NeoForge itself uses to decode a payload from its id - so
 * any mod that registers its networking normally (which is effectively all of them, including things
 * built on top of NeoForge's registrar like Veil) round-trips through a replay without needing to know
 * anything about that specific mod.
 * <p>
 * One payload gets special treatment: NeoForge's own AdvancedAddEntityPayload, which carries
 * IEntityWithComplexSpawn data (e.g. Create's contraption controller position). That data belongs on the
 * server-side entity Flashback reconstructs during playback. If NeoForge tried to pair the entity before
 * this action initialized it, ReplayComplexSpawnPairing repeats that pairing atomically afterwards. See
 * MixinReplayGamePacketHandler and MixinServerEntityNeoForgePairing. Everything else is a plain
 * client-side effect, so it's simply replayed to whichever players are currently watching, the same way
 * it would have reached the original client live.
 */
public class ActionModdedPayload implements Action {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    public static final ResourceLocation NAME =
            ResourceLocation.fromNamespaceAndPath("flashback_neoforge_fixed", "action/modded_payload_optional");
    /** Reads recordings made before payloads were frozen eagerly. */
    public static final ActionModdedPayload INSTANCE = new ActionModdedPayload();

    private static final Set<ResourceLocation> loggedMissingCodecTypes = Collections.synchronizedSet(new HashSet<>());

    private ActionModdedPayload() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    private static final Set<ResourceLocation> loggedUnwritableTypes = Collections.synchronizedSet(new HashSet<>());

    /**
     * Encodes a newly synthesized payload immediately. Inbound payloads must use the immutable wire
     * copy made by InboundPayloadCapture instead: received mod objects may contain a one-shot decoder
     * whose buffer still belongs to their normal client handler.
     */
    @SuppressWarnings("unchecked")
    public static EncodedPayload encodeSynthetic(
            ConnectionProtocol protocol, CustomPacketPayload payload) {
        ResourceLocation id = payload.type().id();
        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec =
                NetworkRegistry.getCodec(id, protocol, PacketFlow.CLIENTBOUND);
        if (codec == null) {
            if (loggedUnwritableTypes.add(id)) {
                LOGGER.warn("No codec registered for payload {}, dropping it from the recording", id);
            }
            return null;
        }

        var connection = Minecraft.getInstance().getConnection();
        var level = Minecraft.getInstance().level;
        if (connection == null && level == null) {
            return null;
        }
        var registryAccess = connection != null ? connection.registryAccess() : level.registryAccess();
        var connectionType = connection != null
                ? connection.getConnectionType()
                : net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE;
        RegistryFriendlyByteBuf encoded = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryAccess, connectionType);
        try {
            ((StreamCodec<FriendlyByteBuf, CustomPacketPayload>) codec).encode(encoded, payload);
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return new EncodedPayload(protocol, id, bytes);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to encode synthesized payload {}; dropping it from the recording",
                    id, exception);
            return null;
        } finally {
            encoded.release();
        }
    }

    public static void write(ReplayWriter writer, EncodedPayload payload) {
        writer.startAction(ActionModdedPayloadV2.INSTANCE);
        RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
        buf.writeEnum(payload.protocol());
        buf.writeResourceLocation(payload.id());
        buf.writeBytes(payload.data());
        writer.finishAction(ActionModdedPayloadV2.INSTANCE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        handlePayload(replayServer, buf, true, false);
    }

    @SuppressWarnings("unchecked")
    static void handlePayload(ReplayServer replayServer, RegistryFriendlyByteBuf buf,
            boolean legacy, boolean trustedSnapshotBatch) {
        ConnectionProtocol protocol = buf.readEnum(ConnectionProtocol.class);
        ResourceLocation id = buf.readResourceLocation();

        // Player/session state is recreated by the mod for the ReplayServer's current viewer. Old
        // recordings cached the original connection's transaction in their snapshot, which can be
        // both stale and only partially captured. Reject it before decoding or forwarding while
        // still consuming the complete action slice for ReplayReader's framing check.
        if (replayServer.isProcessingSnapshot && ReplayPayloadPolicy.isConnectionScoped(id)) {
            int encodedBytes = buf.readableBytes();
            buf.skipBytes(encodedBytes);
            LOGGER.warn("Dropping connection-scoped replay snapshot payload {} ({} encoded byte(s));"
                    + " the replay server will send fresh viewer state", id, encodedBytes);
            return;
        }

        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec =
                NetworkRegistry.getCodec(id, protocol, PacketFlow.CLIENTBOUND);
        if (codec == null) {
            if (loggedMissingCodecTypes.add(id)) {
                LOGGER.warn("No codec registered for recorded payload {} ({}), dropping it", id, protocol);
            }
            // ReplayReader hands this action a slice sized to its recorded length and requires the
            // whole thing read back, whether or not we could make sense of it.
            buf.skipBytes(buf.readableBytes());
            return;
        }

        // A payload's codec can depend on runtime registry state (e.g. Create's bogey blocks are
        // looked up by raw block registry id) that isn't guaranteed stable between the session that
        // recorded this action and the one replaying it now - a different mod list or load order
        // shifts those ids and decodes into an unrelated object. That's unrecoverable for this one
        // payload, not a reason to kill the whole replay tick.
        CustomPacketPayload payload;
        try {
            payload = ((StreamCodec<FriendlyByteBuf, CustomPacketPayload>) codec).decode(buf);
        } catch (RuntimeException exception) {
            if (loggedMissingCodecTypes.add(id)) {
                LOGGER.error("Failed to decode recorded payload {} ({}); dropping it. This usually means the "
                        + "replay was recorded with a different mod list or load order than is installed now",
                        id, protocol, exception);
            }
            // A failed decode can leave the reader index anywhere inside the payload; ReplayReader
            // still expects this action's whole slice consumed, so skip whatever is left of it.
            buf.skipBytes(buf.readableBytes());
            return;
        }

        // Older versions retained mutable/batched payload objects until an asynchronous write. Such
        // actions can contain an already-consumed inner ByteBuf or only the final fragment of a batch.
        // They cannot be repaired from the file. Drop only those capability shapes on the legacy
        // action; v2 freezes every fragment immediately and is safe to deliver.
        boolean mutableOrBatched = isLegacyMutableOrBatched(payload);
        if (legacy && mutableOrBatched) {
            if (loggedMissingCodecTypes.add(id)) {
                LOGGER.warn("Dropping unsafe legacy buffered/batched replay payload {}; make a new recording to preserve it",
                        id);
            }
            return;
        }

        // The first v2 snapshot implementation could mistake one fragment of a countdown-based
        // transaction for a complete last-known-state packet when reflective batch discovery was
        // denied by the payload's Java module. Replaying that fragment contaminates the receiving
        // mod's batch accumulator (and can crash an ObjectInputStream). Timeline v2 actions are
        // unaffected; only untrusted v2 actions found inside an initial/seek snapshot are rejected.
        // v3 snapshot actions are written only after the complete transaction has been captured.
        if (replayServer.isProcessingSnapshot && !trustedSnapshotBatch && mutableOrBatched) {
            if (loggedMissingCodecTypes.add(id)) {
                LOGGER.warn("Dropping incomplete-capable v2 snapshot payload {}; newer recordings preserve its complete transaction",
                        id);
            }
            return;
        }

        if (payload instanceof AdvancedAddEntityPayload advancedAddEntityPayload) {
            var entity = ((ReplayServerComplexSpawnExt) replayServer).flashbackNeoForgeFixed$applyComplexSpawnData(
                    advancedAddEntityPayload.entityId(), advancedAddEntityPayload.customPayload());
            ReplayComplexSpawnPairing.repairIfFailed(replayServer, entity);
            return;
        }

        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        var viewers = replayServer.getReplayViewers();
        if (replayServer.isProcessingSnapshot || viewers.isEmpty()) {
            // Snapshot actions run before pending entities are flushed into the level and tracked to
            // the viewer. Entity-addressed mod payloads sent here would arrive before their entity and
            // be discarded by the receiving mod. Defer every snapshot payload until tick tail, after
            // normal entity tracking, regardless of whether a viewer already happens to be present.
            ((ReplayServerCatchupExt) replayServer).flashbackNeoForgeFixed$queueForNewViewers(packet);
            return;
        }

        // A seek handles the snapshot and then fast-forwards its timeline before tick-tail sends
        // the snapshot backlog to the physical client. Keep those timeline payloads behind the
        // backlog; Sable movement received before StartTracking is irrecoverably discarded.
        if (((ReplayServerCatchupExt) replayServer)
                .flashbackNeoForgeFixed$deferUntilSnapshotDelivered(packet)) {
            return;
        }

        for (ServerPlayer viewer : viewers) {
            viewer.connection.send(packet);
        }
    }

    private static boolean isLegacyMutableOrBatched(CustomPacketPayload payload) {
        Class<?> type = payload.getClass();
        for (Field field : type.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (FriendlyByteBuf.class.isAssignableFrom(fieldType)
                    || ByteBuf.class.isAssignableFrom(fieldType)
                    || isBatchCounter(field.getName(), fieldType)) {
                return true;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && isBatchCounter(method.getName(), method.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBatchCounter(String name, Class<?> valueType) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("batch") && lower.contains("remaining")
                && (valueType == int.class || valueType == long.class
                || Number.class.isAssignableFrom(valueType));
    }

    public record EncodedPayload(ConnectionProtocol protocol, ResourceLocation id, byte[] data) {
    }
}
