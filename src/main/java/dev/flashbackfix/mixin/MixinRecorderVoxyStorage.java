package dev.flashbackfix.mixin;

import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import dev.flashbackfix.compat.VoxyReplayStorageCompat;
import dev.flashbackfix.compat.VoxyReplayStorageHolder;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Voxy's database path when a recording starts, as Voxy does on Fabric. */
@Mixin(Recorder.class)
public abstract class MixinRecorderVoxyStorage {

    @Shadow
    @Final
    private FlashbackMeta metadata;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$captureVoxyStorage(
            RegistryAccess registryAccess, CallbackInfo ci) {
        VoxyReplayStorageCompat.captureRecordingStorage((VoxyReplayStorageHolder) this.metadata);
    }
}
