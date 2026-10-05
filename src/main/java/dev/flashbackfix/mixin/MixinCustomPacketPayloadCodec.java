package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ReplayWirePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends replay payloads from their immutable recording bytes without a lossy re-encode. */
@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public abstract class MixinCustomPacketPayloadCodec {

    @Inject(method = "encode(Lnet/minecraft/network/FriendlyByteBuf;"
            + "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
            at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$writeRecordedBytes(
            FriendlyByteBuf buffer,
            CustomPacketPayload payload,
            CallbackInfo ci) {
        if (!(payload instanceof ReplayWirePayload replayPayload)) {
            return;
        }
        buffer.writeResourceLocation(replayPayload.type().id());
        replayPayload.writeBody(buffer);
        ci.cancel();
    }
}
