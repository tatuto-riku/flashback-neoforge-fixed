package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SuperResolutionCaptureFrameCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents a crash when Super Resolution's capture frame ring is re-entered mid-frame - e.g. when a
 * large Flashback replay rewind triggers a CONFIGURATION-phase reconnect that "seamless loading
 * screen" reacts to by taking a leave screenshot from inside an already-running render call. Without
 * this, that reentrancy trips an assertion in CaptureFrameRing.beginFrame and crashes the game.
 */
@Pseudo
@Mixin(targets = "io.homo.superresolution.common.presentation.capture.CaptureFrameRing",
        remap = false)
public abstract class MixinSuperResolutionFrameRingReentrancy {

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0)
    private void flashbackNeoForgeFixed$recoverFromReentrantFrame(
            int logicalFrameIndex, CallbackInfoReturnable<Object> cir) {
        SuperResolutionCaptureFrameCompat.recoverIfFrameIndexChanged(this, logicalFrameIndex);
    }
}
