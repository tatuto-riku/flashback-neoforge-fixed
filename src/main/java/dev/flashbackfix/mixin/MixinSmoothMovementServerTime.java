package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SmoothMovementReplayCompat;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops Smooth Movement from rescaling the fake replay server's deterministic tick stream. */
@Pseudo
@Mixin(targets = "com.smoothmovement.time.ServerTime", remap = false)
public abstract class MixinSmoothMovementServerTime {

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$keepReplayAtRecordedSpeed(
            MinecraftServer server, long[] tickTimes, int index, CallbackInfo ci) {
        if (SmoothMovementReplayCompat.suppressServerTimingDuringReplay()) {
            ci.cancel();
        }
    }
}
