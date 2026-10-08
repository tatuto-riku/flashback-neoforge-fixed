package dev.flashbackfix.mixin;

import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes Flashback expose its voice-recording option for the native NeoForge Voice Chat mod. */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.configuration.FlashbackConfigV1$SubcategoryRecording",
        remap = false)
public abstract class MixinFlashbackVoiceChatConfig {

    @Inject(method = "hasSimpleVoiceChat", at = @At("RETURN"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$recognizeNeoForgeVoiceChat(
            CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) && ModList.get().isLoaded("voicechat")) {
            cir.setReturnValue(true);
        }
    }
}
