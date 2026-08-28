package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.VoxyReplayOcclusionCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Voxy from drawing its (possibly stale) LOD terrain over chunks Sodium hasn't finished
 * rebuilding yet after Voxy's render system gets recreated mid-replay. Without this, already-loaded
 * chunks briefly show both the live Sodium mesh and Voxy's old snapshot at once - e.g. a door a player
 * just opened rendering as both open and closed until Sodium's rebuild pass catches up.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class MixinVoxyRenderSystemOcclusionGate {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void flashbackNeoForgeFixed$onCreated(CallbackInfo ci) {
        VoxyReplayOcclusionCompat.onRenderSystemCreated();
    }

    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$holdUntilSodiumCatchesUp(CallbackInfo ci) {
        if (VoxyReplayOcclusionCompat.shouldSuppressFrame()) {
            ci.cancel();
        }
    }
}
