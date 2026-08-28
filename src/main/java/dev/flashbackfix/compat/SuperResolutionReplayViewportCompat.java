package dev.flashbackfix.compat;

import com.moulberry.flashback.WindowSizeTracker;
import com.moulberry.flashback.editor.ui.ReplayUI;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Keeps Flashback's replay panel and Super Resolution's render scale in one coordinate space. */
public final class SuperResolutionReplayViewportCompat {

    private static boolean reflectionResolved;
    private static Method isHackSelected;
    private static Method getCurrentScaleFactor;
    private static boolean hackSelected;

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
        if (!ReplayUI.shouldModifyViewport()) {
            return 0;
        }
        var window = Minecraft.getInstance().getWindow();
        float framebufferScale = WindowSizeTracker.getWidth(window)
                / (float) Math.max(1, ReplayUI.viewportSizeX);
        return Math.max(1, Math.round(ReplayUI.frameWidth * framebufferScale));
    }

    public static int getReplayOutputHeight() {
        if (!ReplayUI.shouldModifyViewport()) {
            return 0;
        }
        var window = Minecraft.getInstance().getWindow();
        float framebufferScale = WindowSizeTracker.getHeight(window)
                / (float) Math.max(1, ReplayUI.viewportSizeY);
        return Math.max(1, Math.round(ReplayUI.frameHeight * framebufferScale));
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
