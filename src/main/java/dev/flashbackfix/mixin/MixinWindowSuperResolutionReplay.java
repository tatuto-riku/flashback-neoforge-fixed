package dev.flashbackfix.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.flashbackfix.compat.SuperResolutionReplayViewportCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Combines Flashback's panel size with SR's temporary Iris world-render scale. */
@Mixin(value = Window.class, priority = 2000)
public abstract class MixinWindowSuperResolutionReplay {

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$getScaledReplayWidth(
            CallbackInfoReturnable<Integer> cir) {
        int width = SuperResolutionReplayViewportCompat.getReplayRenderWidth();
        if (width > 0) {
            cir.setReturnValue(width);
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$getScaledReplayHeight(
            CallbackInfoReturnable<Integer> cir) {
        int height = SuperResolutionReplayViewportCompat.getReplayRenderHeight();
        if (height > 0) {
            cir.setReturnValue(height);
        }
    }
}
