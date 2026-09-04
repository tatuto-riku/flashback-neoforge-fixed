package dev.flashbackfix.action;

import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * The direct-to-viewer counterpart of {@link ActionModdedPayload}. Normally NeoForge's complex
 * entity spawn payload is applied to Flashback's reconstructed server entity. Sable plot entities
 * deliberately live only on the viewing client, however, so their payload must follow their direct
 * add-entity packet to that client as well.
 */
public final class ActionForwardedModdedPayload implements Action {

    public static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath(
            "flashback_neoforge_fixed", "action/forwarded_modded_payload_optional");
    public static final ActionForwardedModdedPayload INSTANCE = new ActionForwardedModdedPayload();

    private ActionForwardedModdedPayload() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    public static void write(ReplayWriter writer, ActionModdedPayload.EncodedPayload payload) {
        writer.startAction(INSTANCE);
        RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
        buf.writeEnum(payload.protocol());
        buf.writeResourceLocation(payload.id());
        buf.writeBytes(payload.data());
        writer.finishAction(INSTANCE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        ConnectionProtocol protocol = buf.readEnum(ConnectionProtocol.class);
        ResourceLocation id = buf.readResourceLocation();
        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec =
                NetworkRegistry.getCodec(id, protocol, PacketFlow.CLIENTBOUND);
        if (codec == null) {
            return;
        }

        CustomPacketPayload payload = ((StreamCodec<FriendlyByteBuf, CustomPacketPayload>) codec).decode(buf);
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(payload);
        var viewers = replayServer.getReplayViewers();
        if (replayServer.isProcessingSnapshot || viewers.isEmpty()) {
            ((ReplayServerCatchupExt) replayServer).flashbackNeoForgeFixed$queueForNewViewers(packet);
            return;
        }
        for (ServerPlayer viewer : viewers) {
            viewer.connection.send(packet);
        }
    }
}
