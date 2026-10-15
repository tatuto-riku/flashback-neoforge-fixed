package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps Essential's current online emote state from being projected over an entire replay. */
@Pseudo
@Mixin(targets = "gg.essential.cosmetics.state.EssentialAnimationSystem", remap = false)
public abstract class MixinEssentialAnimationSystemReplay {

    @Inject(method = "processEmoteEvent", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$ignoreOutOfBandReplayEmote(CallbackInfo ci) {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
