package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SmoothMovementReplayCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns the enter/leave lifecycle so a temporary compatibility setting is always restored. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftReplayInterpolation {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$updateReplayInterpolation(
            boolean renderLevel, CallbackInfo ci) {
        SmoothMovementReplayCompat.updateClientState();
    }
}
