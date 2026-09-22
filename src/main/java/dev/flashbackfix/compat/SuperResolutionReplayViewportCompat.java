package dev.flashbackfix.compat;

import com.mojang.blaze3d.platform.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Keeps Flashback's replay panel and Super Resolution's render scale in one coordinate space. */
public final class SuperResolutionReplayViewportCompat {

    private static boolean reflectionResolved;
    private static Method isHackSelected;
    private static Method getCurrentScaleFactor;
    private static boolean hackSelected;

    // Window#getWidth/getHeight is queried while Minecraft itself is still being constructed. Keep
    // every Flashback symbol behind reflection so this optional integration remains inert when
    // Flashback is absent (or Connector has not made its classes visible yet).
    private static volatile boolean replayUiReflectionResolved;
    private static Method shouldModifyViewport;
    private static Method getTrackedWidth;
    private static Method getTrackedHeight;
    private static Field viewportSizeX;
    private static Field viewportSizeY;
    private static Field frameWidth;
    private static Field frameHeight;

    private SuperResolutionReplayViewportCompat() {
    }

    /** The selected work mode can change when Iris reloads a shader pack. */
    public static void refreshWorkMode() {
        if (!ModList.get().isLoaded("super_resolution")) {
            hackSelected = false;
            return;
        }
        resolveReflection();
        try {
            hackSelected = isHackSelected != null
                    && Boolean.TRUE.equals(isHackSelected.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            hackSelected = false;
        }
    }

    public static int getReplayOutputWidth() {
        return getReplayOutputSize(true);
    }

    public static int getReplayOutputHeight() {
        return getReplayOutputSize(false);
    }

    /**
     * Returns SR's momentary world-render size. The scale is deliberately queried on every call:
     * SR changes it from 1 to the configured input ratio only while rendering the world, then puts
     * it back. That is how its own Window mixin keeps GUI/output resolution separate from shaders.
     */
    public static int getReplayRenderWidth() {
        int outputWidth = getReplayOutputWidth();
        if (outputWidth == 0 || !hackSelected) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(outputWidth * getDynamicRenderScale()));
    }

    public static int getReplayRenderHeight() {
        int outputHeight = getReplayOutputHeight();
        if (outputHeight == 0 || !hackSelected) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(outputHeight * getDynamicRenderScale()));
    }

    private static float getDynamicRenderScale() {
        resolveReflection();
        try {
            if (getCurrentScaleFactor != null) {
                Object value = getCurrentScaleFactor.invoke(null);
                if (value instanceof Number number) {
                    float scale = number.floatValue();
                    if (Float.isFinite(scale) && scale > 0.0F) {
                        return scale;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return 1.0F;
    }

    private static int getReplayOutputSize(boolean width) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft == null ? null : minecraft.getWindow();
        if (window == null) {
            return 0;
        }

        resolveReplayUiReflection();
        if (shouldModifyViewport == null) {
            return 0;
        }

        try {
            if (!Boolean.TRUE.equals(shouldModifyViewport.invoke(null))) {
                return 0;
            }
            Method trackedSizeMethod = width ? getTrackedWidth : getTrackedHeight;
            Field viewportSizeField = width ? viewportSizeX : viewportSizeY;
            Field frameSizeField = width ? frameWidth : frameHeight;
            int trackedSize = ((Number) trackedSizeMethod.invoke(null, window)).intValue();
            int viewportSize = ((Number) viewportSizeField.get(null)).intValue();
            int frameSize = ((Number) frameSizeField.get(null)).intValue();
            float framebufferScale = trackedSize / (float) Math.max(1, viewportSize);
            return Math.max(1, Math.round(frameSize * framebufferScale));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            // A changed or partially transformed Flashback must disable only this optional bridge,
            // never the base game. Do not retry reflective calls on every Window size query.
            clearReplayUiReflection();
            return 0;
        }
    }

    private static void resolveReplayUiReflection() {
        if (replayUiReflectionResolved) {
            return;
        }
        synchronized (SuperResolutionReplayViewportCompat.class) {
            if (replayUiReflectionResolved) {
                return;
            }
            try {
                Class<?> replayUi = Class.forName(
                        "com.moulberry.flashback.editor.ui.ReplayUI");
                Class<?> windowSizeTracker = Class.forName(
                        "com.moulberry.flashback.WindowSizeTracker");
                shouldModifyViewport = replayUi.getMethod("shouldModifyViewport");
                viewportSizeX = replayUi.getField("viewportSizeX");
                viewportSizeY = replayUi.getField("viewportSizeY");
                frameWidth = replayUi.getField("frameWidth");
                frameHeight = replayUi.getField("frameHeight");
                getTrackedWidth = windowSizeTracker.getMethod("getWidth", Window.class);
                getTrackedHeight = windowSizeTracker.getMethod("getHeight", Window.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                clearReplayUiReflection();
            } finally {
                replayUiReflectionResolved = true;
            }
        }
    }

    private static void clearReplayUiReflection() {
        shouldModifyViewport = null;
        getTrackedWidth = null;
        getTrackedHeight = null;
        viewportSizeX = null;
        viewportSizeY = null;
        frameWidth = null;
        frameHeight = null;
    }

    private static void resolveReflection() {
        if (reflectionResolved) {
            return;
        }
        reflectionResolved = true;
        try {
            Class<?> irisCompat = Class.forName(
                    "io.homo.superresolution.common.compat.iris.IrisCompatHelper");
            isHackSelected = irisCompat.getMethod("isHackSelected");
            Class<?> renderHandler = Class.forName(
                    "io.homo.superresolution.common.minecraft.handler.RenderHandlerManager");
            getCurrentScaleFactor = renderHandler.getMethod("getCurrentScaleFactor");
        } catch (ReflectiveOperationException | LinkageError exception) {
            isHackSelected = null;
            getCurrentScaleFactor = null;
        }
    }
}
