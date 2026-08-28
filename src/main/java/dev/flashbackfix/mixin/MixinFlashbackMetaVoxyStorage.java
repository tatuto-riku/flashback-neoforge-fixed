package dev.flashbackfix.mixin;

import com.google.gson.JsonObject;
import com.moulberry.flashback.record.FlashbackMeta;
import dev.flashbackfix.compat.VoxyReplayStorageHolder;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoForge port of the Voxy Fabric mixin which persists voxy_storage_path in metadata.json. */
@Mixin(FlashbackMeta.class)
public abstract class MixinFlashbackMetaVoxyStorage implements VoxyReplayStorageHolder {

    @Unique
    private Path flashbackNeoForgeFixed$voxyStoragePath;

    @Override
    public Path flashbackNeoForgeFixed$getVoxyStoragePath() {
        return this.flashbackNeoForgeFixed$voxyStoragePath;
    }

    @Override
    public void flashbackNeoForgeFixed$setVoxyStoragePath(Path path) {
        this.flashbackNeoForgeFixed$voxyStoragePath = path;
    }

    @Inject(method = "toJson", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$writeVoxyStoragePath(
            CallbackInfoReturnable<JsonObject> cir) {
        if (this.flashbackNeoForgeFixed$voxyStoragePath != null) {
            cir.getReturnValue().addProperty("voxy_storage_path",
                    this.flashbackNeoForgeFixed$voxyStoragePath.toString());
        }
    }

    @Inject(method = "fromJson", at = @At("RETURN"))
    private static void flashbackNeoForgeFixed$readVoxyStoragePath(
            JsonObject json, CallbackInfoReturnable<FlashbackMeta> cir) {
        FlashbackMeta metadata = cir.getReturnValue();
        if (metadata != null && json.has("voxy_storage_path")
                && !json.get("voxy_storage_path").isJsonNull()) {
            ((VoxyReplayStorageHolder) metadata).flashbackNeoForgeFixed$setVoxyStoragePath(
                    Path.of(json.get("voxy_storage_path").getAsString()));
        }
    }
}
