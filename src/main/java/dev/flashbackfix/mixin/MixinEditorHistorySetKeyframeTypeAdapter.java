package dev.flashbackfix.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps keyframes stored in Undo/Redo history on Flashback's polymorphic serialization path.
 *
 * <p>Flashback 0.39.10 serializes the history value using its runtime class, but deserializes it as
 * {@link Keyframe}. Built-in keyframes happen to have their own runtime adapters; an externally
 * registered keyframe does not, so its discriminator is omitted. The broken value survives even
 * after the visible keyframe is deleted and then prevents both editor-state copies and reloads.</p>
 */
@Mixin(
        targets = "com.moulberry.flashback.state.EditorSceneHistoryAction$SetKeyframe$TypeAdapter",
        remap = false)
public abstract class MixinEditorHistorySetKeyframeTypeAdapter {

    @Redirect(
            method = "serialize(Lcom/moulberry/flashback/state/EditorSceneHistoryAction$SetKeyframe;"
                    + "Ljava/lang/reflect/Type;Lcom/google/gson/JsonSerializationContext;)"
                    + "Lcom/google/gson/JsonElement;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/gson/JsonSerializationContext;serialize(Ljava/lang/Object;)"
                            + "Lcom/google/gson/JsonElement;",
                    ordinal = 1),
            remap = false)
    private JsonElement flashbackNeoForgeFixed$serializeHistoryKeyframeAsBaseType(
            JsonSerializationContext context, Object keyframe) {
        return context.serialize(keyframe, Keyframe.class);
    }
}
