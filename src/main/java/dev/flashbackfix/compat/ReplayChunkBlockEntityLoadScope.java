package dev.flashbackfix.compat;

/** Marks chunk update-tag decoding performed by Flashback's server-side replay mirror. */
public final class ReplayChunkBlockEntityLoadScope {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ReplayChunkBlockEntityLoadScope() {
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }
}
