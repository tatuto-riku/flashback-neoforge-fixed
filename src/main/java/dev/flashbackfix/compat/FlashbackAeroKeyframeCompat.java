package dev.flashbackfix.compat;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.moulberry.flashback.keyframe.Keyframe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Bridges Flashback: Sable Tracking's custom keyframe through Flashback's closed type adapter.
 *
 * <p>The add-on injects the same bridge itself, but its published mixin uses an optional injection
 * ({@code defaultRequire: 0}). If that injection is skipped, the keyframe is still registered and
 * editable, while saving the editor state fails with Flashback's "unknown keyframe" exception.
 * Export also copies that state before starting, so it remains on the export window. Keeping this
 * small fallback in the NeoForge compatibility layer makes both operations independent of whether
 * the add-on's optional injection was applied.</p>
 *
 * <p>All access is reflective so Flashback: Sable Tracking remains optional.</p>
 */
public final class FlashbackAeroKeyframeCompat {

    private static final String TRACKING_KEYFRAME_CLASS =
            "net.stlx.flashbackaero.keyframe.impl.TrackSublevelKeyframe";
    private static final String TRACKING_ADAPTER_CLASS =
            "net.stlx.flashbackaero.keyframe.serialization.TrackSublevelKeyframeTypeAdapter";
    private static final String TRACKING_TYPE = "track_sublevel";

    private FlashbackAeroKeyframeCompat() {
    }

    public static boolean isTrackingKeyframe(Keyframe keyframe) {
        return keyframe != null && TRACKING_KEYFRAME_CLASS.equals(keyframe.getClass().getName());
    }

    public static boolean isTrackingKeyframeJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return false;
        }

        JsonObject object = json.getAsJsonObject();
        JsonElement type = object.get("type");
        if (type != null && type.isJsonPrimitive() && TRACKING_TYPE.equals(type.getAsString())) {
            return true;
        }

        // Flashback 0.39.10's history adapter used context.serialize(Object), bypassing the
        // polymorphic Keyframe adapter. Recover states written by that path before this fix.
        return !object.has("type")
                && object.has("targetSublevel")
                && object.has("trackingMode")
                && object.has("positionOffset")
                && object.has("viewOffset");
    }

    public static JsonElement serialize(
            Keyframe keyframe, Type type, JsonSerializationContext context) {
        try {
            return (JsonElement) AdapterAccess.SERIALIZE.invoke(
                    AdapterAccess.INSTANCE, keyframe, type, context);
        } catch (InvocationTargetException exception) {
            throw failure("serialize", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw failure("serialize", exception);
        }
    }

    public static Keyframe deserialize(
            JsonElement json, Type type, JsonDeserializationContext context) {
        try {
            return (Keyframe) AdapterAccess.DESERIALIZE.invoke(
                    AdapterAccess.INSTANCE, normalizeForDeserialization(json), type, context);
        } catch (InvocationTargetException exception) {
            throw failure("deserialize", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw failure("deserialize", exception);
        }
    }

    private static IllegalStateException failure(String operation, Throwable cause) {
        return new IllegalStateException(
                "Failed to " + operation + " Flashback: Sable Tracking keyframe", cause);
    }

    private static JsonElement normalizeForDeserialization(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return json;
        }

        JsonObject source = json.getAsJsonObject();
        if (source.has("type")) {
            return json;
        }

        JsonObject normalized = source.deepCopy();
        JsonElement target = normalized.remove("targetSublevel");
        if (target != null) {
            normalized.add("target", target);
        }
        JsonElement interpolation = normalized.remove("interpolationType");
        if (interpolation != null) {
            normalized.add("interpolation_type", interpolation);
        }
        normalized.addProperty("type", TRACKING_TYPE);
        return normalized;
    }

    private static final class AdapterAccess {
        private static final Object INSTANCE;
        private static final Method SERIALIZE;
        private static final Method DESERIALIZE;

        static {
            try {
                ClassLoader loader = FlashbackAeroKeyframeCompat.class.getClassLoader();
                Class<?> keyframeClass = Class.forName(TRACKING_KEYFRAME_CLASS, false, loader);
                Class<?> adapterClass = Class.forName(TRACKING_ADAPTER_CLASS, true, loader);

                Field instanceField = adapterClass.getField("INSTANCE");
                INSTANCE = instanceField.get(null);
                SERIALIZE = findAdapterMethod(adapterClass, "serialize", keyframeClass, JsonElement.class);
                DESERIALIZE = findAdapterMethod(adapterClass, "deserialize", JsonElement.class, keyframeClass);
            } catch (ReflectiveOperationException | LinkageError exception) {
                throw failure("initialize", exception);
            }
        }

        private static Method findAdapterMethod(
                Class<?> adapterClass, String name, Class<?> firstParameter, Class<?> returnType)
                throws NoSuchMethodException {
            for (Method method : adapterClass.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(name)
                        && parameters.length == 3
                        && parameters[0] == firstParameter
                        && returnType.isAssignableFrom(method.getReturnType())) {
                    return method;
                }
            }

            throw new NoSuchMethodException(adapterClass.getName() + '#' + name);
        }
    }
}
