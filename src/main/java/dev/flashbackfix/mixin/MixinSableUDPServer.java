package dev.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records Sable's integrated-server pose stream without changing its transport.
 *
 * <p>Sable handles the local player specially: {@code sendUDPPacketLocal} copies the UDP payload and
 * places it straight onto Sable's client event loop. It never reaches either Minecraft's Connection
 * or {@link MixinSableUDPChannelHandlerClient}. The old workaround made {@code isConnectedTo} return
 * false while recording, which forced every pose update through Minecraft's reliable TCP bundle
 * queue. Around a busy Aeronautics entity that queue grew faster than the integrated server could
 * drain it, producing progressively larger tick stalls. Capture the immutable payload at the local
 * transport boundary instead and leave Sable's normal low-overhead delivery path intact.</p>
 */
@Mixin(SableUDPServer.class)
public class MixinSableUDPServer {

    @Inject(
            method = "sendUDPPacketLocal(Ldev/ryanhcode/sable/network/udp/SableUDPPacket;)V",
            at = @At("HEAD"))
    private void flashbackNeoForgeFixed$recordLocalMovement(
            SableUDPPacket udpPacket, CallbackInfo ci) {
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null || !(udpPacket instanceof CustomPacketPayload payload)) {
            return;
        }

        ActionModdedPayload.EncodedPayload encoded =
                ActionModdedPayload.encodeSynthetic(ConnectionProtocol.PLAY, payload);
        if (encoded != null) {
            recorder.submitCustomTask(writer -> ActionModdedPayload.write(writer, encoded));
        }
    }
}
