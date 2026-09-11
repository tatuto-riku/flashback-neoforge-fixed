package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.InboundPayloadCapture;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/** Copies clientbound custom-payload wire bytes without re-encoding the decoded mod object. */
@Mixin(PacketDecoder.class)
public abstract class MixinPacketDecoderPayloadCapture {

    @Shadow
    @Final
    private ProtocolInfo<? extends PacketListener> protocolInfo;

    // Observe only fully decoded packets at the ordinary output boundary. Unlike redirecting
    // StreamCodec.decode, this composes with networking diagnostics such as Connectivity that wrap
    // the same decoder invocation themselves.
    @Inject(method = "decode", at = @At(value = "INVOKE",
            target = "Ljava/util/List;add(Ljava/lang/Object;)Z"),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void flashbackNeoForgeFixed$capturePayloadWireBytes(
            ChannelHandlerContext context,
            ByteBuf frame,
            List<Object> output,
            CallbackInfo ci,
            int frameLength,
            Packet<?> decoded,
            PacketType<?> packetType) {
        if (this.protocolInfo.flow() == PacketFlow.CLIENTBOUND
                && decoded instanceof ClientboundCustomPayloadPacket packet) {
            int endIndex = frame.readerIndex();
            int startIndex = endIndex - frameLength;
            ConnectionProtocol protocol = this.protocolInfo.id();
            InboundPayloadCapture.capture(
                    protocol, packet.payload(), frame, startIndex, endIndex);
        }
    }
}
