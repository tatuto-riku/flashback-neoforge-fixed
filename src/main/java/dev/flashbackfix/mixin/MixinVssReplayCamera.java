package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.VssReplayCameraCompat;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.flashbackfix.compat.VoxyReplayStorageCompat;

/** Optional VSS integration for detached replay cameras. */
@Pseudo
@Mixin(targets = "dev.xantha.vss.networking.client.LodRequestManager", remap = false)
public abstract class MixinVssReplayCamera {

    @Redirect(method = {"tick", "scanAndSend"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getBlockX()I"),
            require = 0)
    private int flashbackNeoForgeFixed$centerVssOnReplayCameraX(LocalPlayer player) {
        return VssReplayCameraCompat.requestCenterX(player);
    }

    @Redirect(method = {"tick", "scanAndSend"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getBlockZ()I"),
            require = 0)
    private int flashbackNeoForgeFixed$centerVssOnReplayCameraZ(LocalPlayer player) {
        return VssReplayCameraCompat.requestCenterZ(player);
    }

    // A replay database already contains Voxy's recorded LODs. Keep VSS's receive window ticking
    // around the camera so recorded payloads are accepted, but do not ask the fake replay server to
    // generate a second, divergent set of distant chunks.
    @Inject(method = "scanAndSend", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$skipLiveReplayRequests(
            net.minecraft.client.multiplayer.ClientLevel level, LocalPlayer player, CallbackInfo ci) {
        if (VoxyReplayStorageCompat.isReplayStorageActive()) {
            ci.cancel();
        }
    }
}
