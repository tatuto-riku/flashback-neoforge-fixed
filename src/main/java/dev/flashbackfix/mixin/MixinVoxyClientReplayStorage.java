package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.VoxyReplayStorageCompat;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Supplies Voxy's replay database at the late client-instance boundary. PlatformUtilImpl cannot be
 * mixed into safely on NeoForge because Connector loads it while constructing the game module layer,
 * before ordinary DEFAULT-phase mixin configurations are prepared.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class MixinVoxyClientReplayStorage {

    @Inject(method = "getBasePath", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$getReplayStoragePath(
            CallbackInfoReturnable<Path> cir) {
        Path path = VoxyReplayStorageCompat.resolveReplayStoragePath();
        if (path != null) {
            cir.setReturnValue(path);
        }
    }
}
