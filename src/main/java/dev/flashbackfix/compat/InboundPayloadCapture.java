package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.action.ActionModdedPayload;
import io.netty.buffer.ByteBuf;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the immutable wire representation of an inbound custom payload while it is decoded.
 *
 * <p>Received payload objects are not guaranteed to be reusable values. Mods may retain a slice of
 * the network buffer and expose a one-shot decoder that their handler consumes later on the main
 * thread. Encoding such an object for a replay before its handler runs consumes the same buffer and
 * corrupts live gameplay. Copying the already-received bytes avoids invoking any mod code twice.</p>
 */
public final class InboundPayloadCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final int MAX_PENDING = 4096;
    private static final Map<CustomPacketPayload, ActionModdedPayload.EncodedPayload> PENDING =
            new IdentityHashMap<>();

    private InboundPayloadCapture() {
    }

    /**
     * Copies a decoded packet's payload bytes out of the complete packet frame.
     *
     * @param frame packet frame whose reader index has advanced to {@code endIndex}
     * @param startIndex reader index immediately before the protocol packet codec ran
     * @param endIndex reader index immediately after the protocol packet codec ran
     */
    public static void capture(
            ConnectionProtocol protocol,
            CustomPacketPayload payload,
            ByteBuf frame,
            int startIndex,
            int endIndex) {
        if (payload == null
                || Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer
                || startIndex < 0
                || endIndex <= startIndex
                || endIndex > frame.writerIndex()) {
            return;
        }

        try {
            // Every protocol packet starts with its VarInt packet id. A custom-payload packet then
            // starts its body with the payload ResourceLocation. Everything after that id is exactly
            // what the mod's registered StreamCodec consumes and emits.
            FriendlyByteBuf wire = new FriendlyByteBuf(frame.slice(startIndex, endIndex - startIndex));
            wire.readVarInt();
            ResourceLocation wireId = wire.readResourceLocation();
            ResourceLocation decodedId = payload.type().id();
            if (!wireId.equals(decodedId)) {
                LOGGER.error("Inbound custom payload id mismatch: wire {}, decoded {}", wireId, decodedId);
                return;
            }

            byte[] bytes = new byte[wire.readableBytes()];
            wire.getBytes(wire.readerIndex(), bytes);
            ActionModdedPayload.EncodedPayload encoded =
                    new ActionModdedPayload.EncodedPayload(protocol, wireId, bytes);
            remember(payload, encoded);
            if (protocol == ConnectionProtocol.PLAY) {
                ModdedPayloadSnapshotCache.capture(payload, encoded);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to copy inbound custom payload {} from its packet frame",
                    payload.type().id(), exception);
        }
    }

    private static synchronized void remember(
            CustomPacketPayload payload, ActionModdedPayload.EncodedPayload encoded) {
        PENDING.put(payload, encoded);
        while (PENDING.size() > MAX_PENDING) {
            // Entries normally live for less than one client tick. If a mod handler throws before
            // cleanup, clear abandoned strong references as a group rather than letting malformed
            // traffic create a permanent payload-object leak.
            PENDING.clear();
            PENDING.put(payload, encoded);
        }
    }

    /** Returns the immutable bytes associated with this exact decoded payload object. */
    public static synchronized ActionModdedPayload.EncodedPayload get(
            CustomPacketPayload payload, ConnectionProtocol protocol) {
        ActionModdedPayload.EncodedPayload encoded = PENDING.get(payload);
        return encoded != null && encoded.protocol() == protocol ? encoded : null;
    }

    /** Releases the short-lived identity association after the live handler has received it. */
    public static synchronized void discard(CustomPacketPayload payload) {
        PENDING.remove(payload);
    }

    public static synchronized void reset() {
        PENDING.clear();
    }
}
