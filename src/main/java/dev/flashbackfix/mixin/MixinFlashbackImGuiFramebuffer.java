package dev.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.CustomImGuiImplGl3;
import dev.flashbackfix.compat.SuperResolutionImGuiCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents Flashback's ImGui backend from escaping Super Resolution's capture framebuffer. */
@Mixin(CustomImGuiImplGl3.class)
public abstract class MixinFlashbackImGuiFramebuffer {

    @Redirect(method = "setupRenderState",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V",
                    remap = false))
    private void flashbackNeoForgeFixed$keepSuperResolutionCaptureFramebuffer(
            int target, int framebuffer) {
        SuperResolutionImGuiCompat.bindImGuiFramebuffer(target, framebuffer);
    }
}
