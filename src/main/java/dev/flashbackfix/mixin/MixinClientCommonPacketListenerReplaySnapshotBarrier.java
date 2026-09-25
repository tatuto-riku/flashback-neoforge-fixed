package dev.flashbackfix.mixin;

import dev.flashbackfix.FlashbackNeoForgeFixed;
import dev.flashbackfix.compat.SableCompat;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies replay snapshot resets in the client connection's packet order. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientCommonPacketListenerReplaySnapshotBarrier {

    @Inject(method = "handlePing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                    + "Lnet/minecraft/network/protocol/Packet;"
                    + "Lnet/minecraft/network/PacketListener;"
                    + "Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void flashbackNeoForgeFixed$handleReplaySnapshotBarrier(
            ClientboundPingPacket packet, CallbackInfo ci) {
        if (FlashbackNeoForgeFixed.isSableLoaded
                && SableCompat.handleReplaySnapshotBarrier(packet.getId())) {
            // The marker is internal to the integrated replay connection. Do not send a pong that
            // could be mistaken for latency bookkeeping by Flashback's synthetic server player.
            ci.cancel();
        }
    }
}
