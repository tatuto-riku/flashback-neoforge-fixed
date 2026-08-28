package dev.flashbackfix.mixin;

import com.moulberry.flashback.Flashback;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable streams sub-level pose updates over its own raw UDP socket, entirely bypassing Minecraft's
 * Connection - there's no packet on the Connection for Flashback's recording to see, no matter how
 * generic that capture is. Sable already has a Connection-routed fallback for exactly this situation:
 * SubLevelTrackingSystem sends pose updates as an ordinary custom-payload packet via
 * player.connection.send(...) whenever isConnectedTo() reports the player as UDP-unreachable. Forcing
 * that to report false while a recording is in progress routes pose updates through the same
 * Connection-based path MixinRecorder already captures, instead of needing to tap the UDP socket
 * directly. Outside of a recording this changes nothing and Sable's UDP path works as usual.
 */
@Mixin(SableUDPServer.class)
public class MixinSableUDPServer {

    @Inject(method = "isConnectedTo", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$forceConnectionFallbackWhileRecording(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (Flashback.RECORDER != null) {
            cir.setReturnValue(false);
        }
    }
}
