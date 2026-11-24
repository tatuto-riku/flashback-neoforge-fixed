package dev.flashbackfix.mixin;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import dev.flashbackfix.compat.FlashbackAeroKeyframeCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Type;

/** Ensures Sable Tracking keyframes survive editor-state saves and export setup on Connector. */
@Mixin(
        targets = "com.moulberry.flashback.keyframe.Keyframe$TypeAdapter",
        priority = 1100,
        remap = false)
public abstract class MixinFlashbackAeroKeyframeTypeAdapter {

    @Inject(
            method = "serialize(Lcom/moulberry/flashback/keyframe/Keyframe;Ljava/lang/reflect/Type;"
                    + "Lcom/google/gson/JsonSerializationContext;)Lcom/google/gson/JsonElement;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void flashbackNeoForgeFixed$serializeSableTrackingKeyframe(
            Keyframe keyframe,
            Type type,
            JsonSerializationContext context,
            CallbackInfoReturnable<JsonElement> cir) {
        if (FlashbackAeroKeyframeCompat.isTrackingKeyframe(keyframe)) {
            cir.setReturnValue(FlashbackAeroKeyframeCompat.serialize(keyframe, type, context));
        }
    }

    @Inject(
            method = "deserialize(Lcom/google/gson/JsonElement;Ljava/lang/reflect/Type;"
                    + "Lcom/google/gson/JsonDeserializationContext;)Lcom/moulberry/flashback/keyframe/Keyframe;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void flashbackNeoForgeFixed$deserializeSableTrackingKeyframe(
            JsonElement json,
            Type type,
            JsonDeserializationContext context,
            CallbackInfoReturnable<Keyframe> cir) {
        if (FlashbackAeroKeyframeCompat.isTrackingKeyframeJson(json)) {
            cir.setReturnValue(FlashbackAeroKeyframeCompat.deserialize(json, type, context));
        }
    }
}
