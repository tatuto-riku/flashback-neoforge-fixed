package dev.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.ryanhcode.sable.network.udp.AddressedSableUDPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import dev.ryanhcode.sable.network.udp.handler.SableUDPChannelHandlerClient;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records Sable's remote-server pose stream, which never enters Minecraft's Connection.
 *
 * <p>Sable's movement and interpolation-info packets implement both its UDP packet interface and
 * NeoForge's {@link CustomPacketPayload}. The TCP fallback sends those same objects through the
 * normal connection, but an authenticated dedicated-server client receives them only in this
 * Netty handler. Preserve the decoded UDP packet immediately as its registered TCP payload so the
 * replay uses Sable's own codec and handler instead of maintaining a second wire implementation.
 *
 * <p>The integrated-server path remains handled by {@link MixinSableUDPServer}: it forces Sable's
 * existing TCP fallback while recording. Capturing here is the corresponding client-only path for
 * a dedicated server, whose JVM cannot observe {@code Flashback.RECORDER}.
 */
@Mixin(SableUDPChannelHandlerClient.class)
public class MixinSableUDPChannelHandlerClient {

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;"
                    + "Ldev/ryanhcode/sable/network/udp/AddressedSableUDPPacket;)V",
            at = @At("HEAD"))
    private void flashbackNeoForgeFixed$recordRemoteMovement(
            ChannelHandlerContext context,
            AddressedSableUDPPacket addressedPacket,
            CallbackInfo ci) {
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null) {
            return;
        }

        SableUDPPacket udpPacket = addressedPacket.packet();
        if (!(udpPacket instanceof CustomPacketPayload payload)) {
            return;
        }

        ActionModdedPayload.EncodedPayload encoded =
                ActionModdedPayload.encodeSynthetic(ConnectionProtocol.PLAY, payload);
        if (encoded != null) {
            recorder.submitCustomTask(writer -> ActionModdedPayload.write(writer, encoded));
        }
    }
}
