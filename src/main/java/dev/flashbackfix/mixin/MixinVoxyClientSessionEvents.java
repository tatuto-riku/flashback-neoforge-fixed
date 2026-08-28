package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.VoxyReplayCompat;
import dev.flashbackfix.compat.VoxyReplayStorageCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Deletes deferred replay data only after Voxy has closed its world database. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.ClientSessionEvents", remap = false)
public abstract class MixinVoxyClientSessionEvents {

    @Inject(method = "sessionEnd", at = @At("TAIL"), require = 0)
    private static void flashbackNeoForgeFixed$deleteReplayTempAfterVoxyShutdown(CallbackInfo ci) {
        VoxyReplayCompat.flushDeferredDeletes();
        VoxyReplayStorageCompat.clearReplayStorageState();
    }
}
