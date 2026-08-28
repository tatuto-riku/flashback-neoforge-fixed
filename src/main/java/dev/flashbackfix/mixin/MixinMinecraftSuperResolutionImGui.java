package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SuperResolutionImGuiCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flushes ReplayUI's resize before Super Resolution's default-priority frame-begin hook. */
@Mixin(value = Minecraft.class, priority = 1100)
public class MixinMinecraftSuperResolutionImGui {

    @Inject(method = "runTick", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$resizeBeforeSuperResolutionFrame(
            boolean renderLevel, CallbackInfo ci) {
        SuperResolutionImGuiCompat.flushDeferredResize();
    }
}
