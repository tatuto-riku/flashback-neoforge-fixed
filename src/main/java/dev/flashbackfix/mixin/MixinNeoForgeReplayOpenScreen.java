package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handlers.ClientPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents recorded NeoForge menu interactions from opening live screens during playback. */
@Mixin(ClientPayloadHandler.class)
public class MixinNeoForgeReplayOpenScreen {

    @Inject(method = "handle(Lnet/neoforged/neoforge/network/payload/AdvancedOpenScreenPayload;"
            + "Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
            at = @At("HEAD"), cancellable = true)
    private static void flashbackNeoForgeFixed$ignoreRecordedAdvancedMenu(
            AdvancedOpenScreenPayload payload, IPayloadContext context, CallbackInfo ci) {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
