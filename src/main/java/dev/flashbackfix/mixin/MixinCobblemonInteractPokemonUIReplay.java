package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Final safety boundary preventing a recorded Cobblemon interaction wheel from capturing input. */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.client.net.gui.InteractPokemonUIPacketHandler",
        remap = false)
public abstract class MixinCobblemonInteractPokemonUIReplay {

    @Inject(method = "handle(Lcom/cobblemon/mod/common/net/messages/client/ui/"
            + "InteractPokemonUIPacket;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$skipRecordedInteractionWheel(CallbackInfo ci) {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
