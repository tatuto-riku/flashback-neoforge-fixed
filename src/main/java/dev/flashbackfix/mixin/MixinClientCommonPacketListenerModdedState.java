package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.InboundPayloadCapture;
import dev.flashbackfix.compat.ReplayDeferredEntityPayloads;
import dev.flashbackfix.compat.ReplayPayloadPolicy;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the live play connection before NeoForge dispatches a modded payload to its handler. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientCommonPacketListenerModdedState {

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$cacheModdedClientState(
            ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        // Enforce replay policy at the final client dispatch boundary as well. Older recordings and
        // Connector-capable vanilla packet batches can reach this method without going through our
        // custom action. This also rejects invalid session references freshly synthesized by a
        // data-less ReplayServer.
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer
                && (ReplayPayloadPolicy.isTransientClientEffect(packet.payload().type().id())
                || ReplayPayloadPolicy.isConnectionScoped(packet.payload().type().id()))) {
            InboundPayloadCapture.discard(packet.payload());
            ci.cancel();
            return;
        }
        if ((Object) this instanceof ClientPacketListener listener) {
            boolean deferred;
            try {
                deferred = ReplayDeferredEntityPayloads.defer(listener, packet);
            } finally {
                // The snapshot cache and an active Recorder have both seen the immutable wire copy
                // by this point. Forget only that association; never touch the mod's live payload.
                InboundPayloadCapture.discard(packet.payload());
            }
            if (deferred) {
                ci.cancel();
            }
        }
    }
}
