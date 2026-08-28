package dev.flashbackfix.compat;

import java.lang.reflect.Method;

/** Reads Flashback's replay state without linking its Fabric entrypoint interfaces. */
public final class ReplayStateCompat {

    private static boolean resolved;
    private static Method isInReplay;

    private ReplayStateCompat() {
    }

    public static boolean isInReplay() {
        try {
            if (!resolved) {
                Class<?> flashback = Class.forName("com.moulberry.flashback.Flashback");
                isInReplay = flashback.getMethod("isInReplay");
                resolved = true;
            }
            return isInReplay != null && Boolean.TRUE.equals(isInReplay.invoke(null));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }
}
