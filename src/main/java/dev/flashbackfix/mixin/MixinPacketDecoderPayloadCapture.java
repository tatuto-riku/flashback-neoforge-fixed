package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.InboundPayloadCapture;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Copies clientbound custom-payload wire bytes without re-encoding the decoded mod object. */
@Mixin(PacketDecoder.class)
public abstract class MixinPacketDecoderPayloadCapture {

    @Shadow
    @Final
    private ProtocolInfo<? extends PacketListener> protocolInfo;

    @Redirect(method = "decode", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;decode(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object flashbackNeoForgeFixed$capturePayloadWireBytes(
            StreamCodec<Object, Object> codec, Object input) {
        ByteBuf frame = (ByteBuf) input;
        int startIndex = frame.readerIndex();
        Object decoded = codec.decode(input);
        int endIndex = frame.readerIndex();
        if (this.protocolInfo.flow() == PacketFlow.CLIENTBOUND
                && decoded instanceof ClientboundCustomPayloadPacket packet) {
            ConnectionProtocol protocol = this.protocolInfo.id();
            InboundPayloadCapture.capture(
                    protocol, packet.payload(), frame, startIndex, endIndex);
        }
        return decoded;
    }
}
