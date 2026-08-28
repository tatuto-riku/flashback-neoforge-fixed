package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SuperResolutionImGuiCompat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resets per-frame state; drawing itself is hooked directly into Super Resolution's capture. */
@Mixin(value = GameRenderer.class, priority = 1100)
public class MixinGameRendererSuperResolutionImGui {

    @Inject(method = "render", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$beginSuperResolutionFrame(
            DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        SuperResolutionImGuiCompat.beginGameRender();
    }

}
