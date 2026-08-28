package dev.flashbackfix.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recovers Super Resolution's {@code CaptureFrameRing} when it is re-entered mid-frame instead of
 * letting it crash the game.
 *
 * <p>A large replay rewind makes Flashback switch the replay connection through the CONFIGURATION
 * protocol phase, which the "seamless loading screen" mod treats as "the server ordered a disconnect"
 * and reacts to by synchronously taking a leave screenshot. Taking that screenshot pumps a second,
 * nested {@code Minecraft.runTick()}/{@code GameRenderer.render()} call from inside the render call
 * that is already in progress. Super Resolution's frame capture ring only tracks one in-progress frame
 * at a time and asserts that {@code beginFrame} is never called with a different frame index while a
 * frame is still open; the nested render call trips that assertion and crashes the game. Since the
 * nested render is only there to grab a screenshot and never reaches the screen, discarding whatever
 * frame Super Resolution had open and letting it start fresh is safe.
 */
public final class SuperResolutionCaptureFrameCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    private static boolean reflectionResolved;
    private static Field activeFrameField;
    private static Method logicalFrameIndexMethod;
    private static Method finishFrameMethod;

    private SuperResolutionCaptureFrameCompat() {
    }

    public static void recoverIfFrameIndexChanged(Object ring, int logicalFrameIndex) {
        try {
            if (!resolveReflection(ring)) {
                return;
            }
            Object activeFrame = activeFrameField.get(ring);
            if (activeFrame == null) {
                return;
            }
            int activeIndex = (Integer) logicalFrameIndexMethod.invoke(activeFrame);
            if (activeIndex != logicalFrameIndex) {
                LOGGER.warn("Recovering Super Resolution's capture frame ring from a re-entrant render "
                                + "call (frame index changed from {} to {}) instead of crashing",
                        activeIndex, logicalFrameIndex);
                finishFrameMethod.invoke(ring);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("Unable to guard Super Resolution's capture frame ring against reentrancy",
                    exception);
        }
    }

    private static boolean resolveReflection(Object ring) {
        if (reflectionResolved) {
            return activeFrameField != null;
        }
        reflectionResolved = true;
        try {
            Class<?> ringClass = ring.getClass();
            activeFrameField = ringClass.getDeclaredField("activeFrame");
            activeFrameField.setAccessible(true);
            Class<?> frameResourcesClass = activeFrameField.getType();
            logicalFrameIndexMethod = frameResourcesClass.getMethod("logicalFrameIndex");
            finishFrameMethod = ringClass.getDeclaredMethod("finishFrame");
            finishFrameMethod.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("Super Resolution's capture frame ring layout changed; reentrancy guard disabled",
                    exception);
            activeFrameField = null;
            return false;
        }
    }
}
