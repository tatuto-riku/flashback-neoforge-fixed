package dev.flashbackfix.action;

import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Records an ordinary vanilla game packet and, on playback, sends it straight to whichever players
 * are currently watching instead of letting Flashback apply it to the fake replay server.
 * <p>
 * Flashback's own packet handling is built around one assumption: every packet describes something at
 * a position the replay server's reconstructed world actually cares about, so packets get applied to
 * that world and any effect on viewers comes from the world's own tracking re-syncing them normally.
 * That assumption breaks for anything whose position is meaningless to the replay server but still
 * needs to reach the viewer directly - e.g. a mod that stores content in real chunks at some far-off,
 * player-independent coordinate and renders it there with its own transform (Sable's physics
 * sub-levels are the case this was built for, but the mechanism itself doesn't know anything about
 * Sable: it just re-sends whatever packet it's given). See SableCompat for the one place that decides
 * which packets need this treatment.
 * <p>
 * This reuses GameProtocols' own per-packet-type codec (the same one Recorder's normal packet writing
 * uses) rather than NetworkRegistry, because the packets in question are ordinary vanilla types
 * (chunk data, block updates, entity spawns, ...), not modded custom payloads.
 */
public class ActionForwardedGamePacket implements Action {

    public static final ResourceLocation NAME =
            ResourceLocation.fromNamespaceAndPath("flashback_neoforge_fixed", "action/forwarded_game_packet_optional");
    public static final ActionForwardedGamePacket INSTANCE = new ActionForwardedGamePacket();

    private ActionForwardedGamePacket() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    public static void write(ReplayWriter writer, Packet<? super ClientGamePacketListener> packet) {
        writer.startAction(INSTANCE);
        RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
        codecFor(buf).encode(buf, packet);
        writer.finishAction(INSTANCE);
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        Packet<? super ClientGamePacketListener> packet = codecFor(buf).decode(buf);
        var viewers = replayServer.getReplayViewers();
        if (replayServer.isProcessingSnapshot || viewers.isEmpty()) {
            // A replay seek processes StartTracking, plot chunks, Finalize, direct entity spawns and
            // passenger packets as one ordered snapshot transaction. Modded payload actions already
            // wait for the transaction to finish; ordinary forwarded packets must join the same
            // queue. Sending chunks/entities immediately to an existing viewer lets the later
            // StartTracking handler remove the old sub-level (and everything just restored into it).
            // This is also needed on the initial snapshot before a viewer has finished connecting.
            ((ReplayServerCatchupExt) replayServer).flashbackNeoForgeFixed$queueForNewViewers(packet);
            return;
        }

        if (((ReplayServerCatchupExt) replayServer)
                .flashbackNeoForgeFixed$deferUntilSnapshotDelivered(packet)) {
            return;
        }

        for (ServerPlayer viewer : viewers) {
            viewer.connection.send(packet);
        }
    }

    private static StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codecFor(RegistryFriendlyByteBuf buf) {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(buf.registryAccess())).codec();
    }
}
