package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents normal Cobblemon world spawning from running inside Flashback's data-less replay world. */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner", remap = false)
public abstract class MixinCobblemonPlayerSpawnerReplay {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$skipReplayWorldSpawning(CallbackInfo ci) {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
