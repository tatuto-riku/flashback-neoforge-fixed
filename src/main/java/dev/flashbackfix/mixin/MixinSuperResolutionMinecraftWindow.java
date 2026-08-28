package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SuperResolutionReplayViewportCompat;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Super Resolution use Flashback's game panel as its world-rendering screen size.
 *
 * <p>Super Resolution intentionally reads Window's private framebuffer fields through an accessor.
 * That is normally useful, but it bypasses Flashback's Window#getWidth/getHeight overrides. With an
 * Iris shader pack active, SR consequently builds and upscales a full-window image while Minecraft's
 * actual replay render target is only the central game panel. Copying between those differently
 * sized coordinate spaces shifts the visible projection away from the camera's targeting ray.
 * Vulkan presentation must still use the physical window size, so only SR's logical getWindowSize
 * query is changed; its direct GLFW source-size query remains untouched.
 */
@Pseudo
@Mixin(targets = "io.homo.superresolution.common.minecraft.MinecraftWindow", remap = false)
public abstract class MixinSuperResolutionMinecraftWindow {

    @Inject(method = "getWindowSize", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$useReplayGamePanelSize(
            CallbackInfoReturnable<Vector2f> cir) {
        int gameWidth = SuperResolutionReplayViewportCompat.getReplayOutputWidth();
        int gameHeight = SuperResolutionReplayViewportCompat.getReplayOutputHeight();
        if (gameWidth == 0 || gameHeight == 0) {
            return;
        }
        cir.setReturnValue(new Vector2f(gameWidth, gameHeight));
    }
}
