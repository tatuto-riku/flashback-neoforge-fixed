package dev.flashbackfix.compat;

import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A recorded custom payload whose original wire representation must reach the replay client
 * unchanged.
 *
 * <p>Decoding and then encoding is not an identity operation for every mod protocol. Some packets
 * deliberately defer decoding until their client handler runs; Cobblemon's data-registry sync
 * packets, for example, decode into an object whose ordinary encoder sees an empty entry list. Keep
 * those bytes opaque on the replay server and let the real client codec decode them exactly once.</p>
 */
public final class ReplayWirePayload implements CustomPacketPayload {

    private final Type<ReplayWirePayload> type;
    private final byte[] data;

    public ReplayWirePayload(ResourceLocation id, byte[] data) {
        this.type = new Type<>(id);
        this.data = Arrays.copyOf(data, data.length);
    }

    @Override
    public Type<ReplayWirePayload> type() {
        return this.type;
    }

    /** Writes only the payload body; the enclosing custom-payload codec writes the identifier. */
    public void writeBody(FriendlyByteBuf buffer) {
        buffer.writeBytes(this.data);
    }
}
