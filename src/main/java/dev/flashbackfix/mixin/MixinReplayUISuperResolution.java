package dev.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import dev.flashbackfix.compat.SuperResolutionImGuiCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReplayUI.class)
public class MixinReplayUISuperResolution {

    @Inject(method = "drawOverlay", at = @At("HEAD"), cancellable = true)
    private static void flashbackNeoForgeFixed$suppressPostPresentationDraw(CallbackInfo ci) {
        if (SuperResolutionImGuiCompat.shouldSuppressLateDraw()) {
            ci.cancel();
        }
    }

    @Redirect(method = "drawOverlayInternal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;resizeDisplay()V"))
    private static void flashbackNeoForgeFixed$deferResizeDuringFrameCapture(Minecraft minecraft) {
        if (!SuperResolutionImGuiCompat.deferResizeIfCapturing()) {
            minecraft.resizeDisplay();
        }
    }

    @Redirect(method = "transitionActiveState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;resizeDisplay()V"))
    private static void flashbackNeoForgeFixed$deferActivationResizeDuringFrameCapture(
            Minecraft minecraft) {
        if (!SuperResolutionImGuiCompat.deferResizeIfCapturing()) {
            minecraft.resizeDisplay();
        }
    }
}
