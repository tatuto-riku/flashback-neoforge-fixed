package dev.flashbackfix.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.moulberry.flashback.WindowSizeTracker;
import com.moulberry.flashback.editor.ui.ReplayUI;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps Flashback's late ImGui pass inside Super Resolution's captured final frame. */
public final class SuperResolutionImGuiCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static boolean renderingEarly;
    private static boolean renderedEarly;
    private static boolean resizePending;
    private static boolean superResolutionReflectionResolved;
    private static Method getOriginFramebuffer;
    private static Method asMinecraftRenderTarget;
    private static TextureTarget fullFrameTarget;
    private static Object fullFrameTexture;
    private static boolean fullFrameTextureResolutionAttempted;
    private static boolean finalCapturePending;

    private SuperResolutionImGuiCompat() {
    }

    public static boolean isActive() {
        return ModList.get().isLoaded("super_resolution");
    }

    public static void beginGameRender() {
        if (isActive()) {
            SuperResolutionReplayViewportCompat.refreshWorkMode();
            renderedEarly = false;
            finalCapturePending = false;
        }
    }

    /** A resize during Super Resolution's capture changes its frame index and crashes the client. */
    public static boolean deferResizeIfCapturing() {
        if (!isActive() || !renderingEarly) {
            return false;
        }
        resizePending = true;
        return true;
    }

    /** Runs before Super Resolution begins the next frame, outside its capture transaction. */
    public static void flushDeferredResize() {
        if (!isActive() || !resizePending) {
            return;
        }
        resizePending = false;
        Minecraft.getInstance().resizeDisplay();
    }

    /**
     * Super Resolution captures/presents the main target as GameRenderer returns. Flashback normally
     * draws after Minecraft.blitToScreen, which is too late (and that blit is skipped in Vulkan
     * presentation mode), so render the same pass immediately before Super Resolution's capture.
     */
    public static void drawBeforeFinalFrameCapture() {
        if (!isActive() || !ReplayStateCompat.isInReplay()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        int previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);

        renderingEarly = true;
        try {
            // Vulkan presentation skips Minecraft's final blit. Flashback relies on that blit to
            // place the smaller game framebuffer inside the editor's central panel, then draws ImGui
            // over the full real window. Rebuild that exact composition in a full-size texture and
            // let FrameCaptureManager copy this texture instead of the game-only origin texture.
            RenderTarget captureTarget = prepareFullFrameTarget(minecraft);
            captureTarget.bindWrite(true);
            ReplayUI.drawOverlay();
            renderedEarly = true;
            // Only valid for captureFinalColor's own originColorTexture() call, which happens right
            // after this method returns (captureFinalColor's caller triggered us from its own HEAD).
            // captureHudlessColor() runs earlier in the same frame, before this method has composited
            // anything for the frame, and must keep seeing the natural, un-repositioned mainTarget -
            // it feeds Super Resolution's spatial upscaler, which needs the raw render-resolution
            // image, not our HUD-included, panel-repositioned one.
            finalCapturePending = captureTarget == fullFrameTarget && fullFrameTexture != null;
        } finally {
            renderingEarly = false;
            // Never restore these through raw LWJGL calls. Iris tracks framebuffer binds made
            // through GlStateManager and skips binds it believes are redundant. Bypassing that
            // tracker leaves Iris' cached FBO different from OpenGL's real FBO, so the next shader
            // frame can be rendered with the full-window compositor's state instead of Flashback's
            // game viewport. The visible projection then shifts while the camera ray remains
            // correct, which presents as an off-centre view and block targeting.
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GlStateManager._viewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
        }
    }

    /**
     * Returned from FrameCaptureManager.originColorTexture. Super Resolution calls this from two
     * different capture points per frame: captureHudlessColor() (mid-frame, before HUD/ImGui is
     * drawn - feeds the spatial upscaler, which needs the natural, un-repositioned mainRenderTarget)
     * and captureFinalColor() (frame end, after everything is drawn - the actual frame to present).
     * Only the latter should ever see our composited, panel-repositioned, HUD-included texture; the
     * former must fall through to null so Super Resolution keeps using its own natural origin buffer.
     * finalCapturePending is set true only for the one originColorTexture() call that immediately
     * follows drawBeforeFinalFrameCapture() finishing (captureFinalColor's own call) and is consumed
     * here so any earlier call this frame (captureHudlessColor) still sees it false.
     */
    public static Object getFinalCaptureTexture() {
        if (!finalCapturePending || fullFrameTexture == null) {
            return null;
        }
        finalCapturePending = false;
        return fullFrameTexture;
    }

    /**
     * Flashback's custom ImGui renderer unconditionally binds framebuffer 0 while setting up its
     * render state. Under Super Resolution's Vulkan presentation, framebuffer 0 belongs to the
     * hidden OpenGL context and is not the color target copied for presentation. Keep the origin
     * target which {@link #drawBeforeFinalFrameCapture()} bound instead.
     */
    public static void bindImGuiFramebuffer(int target, int requestedFramebuffer) {
        int framebuffer = requestedFramebuffer;
        if (renderingEarly && requestedFramebuffer == 0) {
            framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        }
        // Keep Iris' framebuffer tracker synchronized with the actual OpenGL binding.
        GlStateManager._glBindFramebuffer(target, framebuffer);
    }

    private static RenderTarget resolveCaptureTarget(Minecraft minecraft) {
        try {
            if (!superResolutionReflectionResolved) {
                Class<?> api = Class.forName("io.homo.superresolution.api.SuperResolutionAPI");
                getOriginFramebuffer = api.getMethod("getOriginMinecraftFrameBuffer");
                superResolutionReflectionResolved = true;
            }
            Object origin = getOriginFramebuffer.invoke(null);
            if (origin != null) {
                if (asMinecraftRenderTarget == null
                        || asMinecraftRenderTarget.getDeclaringClass() != origin.getClass()) {
                    asMinecraftRenderTarget = origin.getClass().getMethod("asMcRenderTarget");
                }
                Object target = asMinecraftRenderTarget.invoke(origin);
                if (target instanceof RenderTarget renderTarget) {
                    return renderTarget;
                }
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            // The fallback is still correct when Super Resolution does not replace Minecraft's target.
        }
        return minecraft.getMainRenderTarget();
    }

    private static RenderTarget prepareFullFrameTarget(Minecraft minecraft) {
        int width = Math.max(1, WindowSizeTracker.getWidth(minecraft.getWindow()));
        int height = Math.max(1, WindowSizeTracker.getHeight(minecraft.getWindow()));

        if (fullFrameTarget == null) {
            fullFrameTarget = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        } else if (fullFrameTarget.width != width || fullFrameTarget.height != height) {
            fullFrameTarget.resize(width, height, Minecraft.ON_OSX);
        }

        fullFrameTarget.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        fullFrameTarget.clear(Minecraft.ON_OSX);
        fullFrameTarget.bindWrite(true);

        // Flashback's RenderTarget mixin applies frameX/frameY/frameWidth/frameHeight here, exactly
        // as its normal post-render blit would. Rebind afterward in case another renderer changed it.
        minecraft.getMainRenderTarget().blitToScreen(width, height);
        fullFrameTarget.bindWrite(true);

        Object texture = createFullFrameTextureAdapter();
        if (texture != null) {
            fullFrameTexture = texture;
            return fullFrameTarget;
        }

        // Unknown Super Resolution versions retain the previous visible fallback rather than
        // passing an object with an incompatible ABI to the capture manager.
        return resolveCaptureTarget(minecraft);
    }

    private static Object createFullFrameTextureAdapter() {
        if (fullFrameTextureResolutionAttempted && fullFrameTexture == null) {
            return null;
        }
        if (fullFrameTexture != null) {
            return fullFrameTexture;
        }
        fullFrameTextureResolutionAttempted = true;
        try {
            Class<?> textureFormat = Class.forName(
                    "io.homo.superresolution.core.graphics.impl.texture.TextureFormat");
            Object rgba8 = textureFormat.getField("RGBA8").get(null);
            Class<?> adapter = Class.forName(
                    "io.homo.superresolution.core.graphics.opengl.framebuffer.GlOnlyNameTexture");
            Constructor<?> constructor = adapter.getConstructor(
                    Supplier.class, Supplier.class, Supplier.class, Supplier.class);
            Supplier<Object> format = () -> rgba8;
            Supplier<Integer> width = () -> fullFrameTarget.width;
            Supplier<Integer> height = () -> fullFrameTarget.height;
            Supplier<Long> handle = () -> (long) fullFrameTarget.getColorTextureId();
            fullFrameTexture = constructor.newInstance(format, width, height, handle);
            return fullFrameTexture;
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("Unable to create Super Resolution full-frame ImGui texture adapter",
                    exception);
            return null;
        }
    }

    /** Cancels only Flashback's original post-blit invocation, never our capture-time invocation. */
    public static boolean shouldSuppressLateDraw() {
        return isActive() && renderedEarly && !renderingEarly;
    }
}
