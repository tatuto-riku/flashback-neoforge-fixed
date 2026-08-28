package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SuperResolutionImGuiCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws at the exact boundary before Super Resolution copies its final OpenGL color texture. */
@Pseudo
@Mixin(targets = "io.homo.superresolution.common.presentation.capture.FrameCaptureManager",
        remap = false)
public abstract class MixinSuperResolutionFinalCapture {

    @Inject(method = "captureFinalColor", at = @At("HEAD"), require = 0)
    private static void flashbackNeoForgeFixed$drawReplayUiIntoCapturedTexture(CallbackInfo ci) {
        SuperResolutionImGuiCompat.drawBeforeFinalFrameCapture();
    }

    @Inject(method = "originColorTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$useCompositedReplayTexture(
            CallbackInfoReturnable<Object> cir) {
        Object texture = SuperResolutionImGuiCompat.getFinalCaptureTexture();
        if (texture != null) {
            cir.setReturnValue(texture);
        }
    }
}
