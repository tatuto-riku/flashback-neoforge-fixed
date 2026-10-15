package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents recorded vanilla container interactions from taking over the replay viewer's UI. */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerReplayMenus {

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$ignoreRecordedMenu(
            ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
