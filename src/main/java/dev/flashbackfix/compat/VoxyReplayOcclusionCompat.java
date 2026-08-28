package dev.flashbackfix.compat;

import java.lang.reflect.Method;

/**
 * Holds back Voxy's LOD terrain draw while Sodium is still rebuilding chunks right after Voxy
 * (re)creates its render system during a Flashback replay.
 *
 * <p>Flashback's replay bootstrap places the local client into the replay world in two steps (the
 * recorded player is joined server-side first, then the actual "Replay Viewer" connection follows a
 * moment later). The second step swaps in a fresh ClientLevel, which makes Voxy tear down and rebuild
 * its whole render system (see Voxy's {@code LevelRenderer.allChanged()} hook) and forces Sodium to
 * rebuild every chunk section from scratch. Voxy only stops drawing its stale terrain copy for a chunk
 * once Sodium reports that chunk's section as built again, so until that rebuild pass finishes, Voxy
 * keeps drawing its old snapshot on top of chunks Sodium has already reloaded live - most visible as a
 * duplicated block on anything that changed since the snapshot was captured, e.g. a door a player opens.
 */
public final class VoxyReplayOcclusionCompat {

    private static final int REQUIRED_STABLE_FRAMES = 3;
    private static final long TIMEOUT_FRAMES = 600;

    private static boolean reflectionResolved;
    private static Method instanceNullable;
    private static Method isTerrainRenderComplete;

    private static boolean catchingUp;
    private static int stableFrames;
    private static long deadlineFrame;
    private static long frameCounter;

    private VoxyReplayOcclusionCompat() {
    }

    public static void onRenderSystemCreated() {
        if (!ReplayStateCompat.isInReplay()) {
            catchingUp = false;
            return;
        }
        catchingUp = true;
        stableFrames = 0;
        deadlineFrame = frameCounter + TIMEOUT_FRAMES;
    }

    public static boolean shouldSuppressFrame() {
        frameCounter++;
        if (!catchingUp) {
            return false;
        }
        if (!ReplayStateCompat.isInReplay() || frameCounter > deadlineFrame) {
            catchingUp = false;
            return false;
        }

        Boolean complete = isSodiumTerrainRenderComplete();
        if (complete == null || complete) {
            if (++stableFrames >= REQUIRED_STABLE_FRAMES) {
                catchingUp = false;
                return false;
            }
        } else {
            stableFrames = 0;
        }
        return true;
    }

    private static Boolean isSodiumTerrainRenderComplete() {
        try {
            if (!reflectionResolved) {
                Class<?> sodiumRenderer =
                        Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                instanceNullable = sodiumRenderer.getMethod("instanceNullable");
                isTerrainRenderComplete = sodiumRenderer.getMethod("isTerrainRenderComplete");
                reflectionResolved = true;
            }
            Object renderer = instanceNullable.invoke(null);
            if (renderer == null) {
                return null;
            }
            return (Boolean) isTerrainRenderComplete.invoke(renderer);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }
}
