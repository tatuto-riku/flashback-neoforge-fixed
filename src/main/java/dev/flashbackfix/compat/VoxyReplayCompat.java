package dev.flashbackfix.compat;

import com.moulberry.flashback.TempFolderProvider;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps Voxy's replay database alive until its client session releases the RocksDB lock. */
public final class VoxyReplayCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final List<PendingDelete> PENDING_DELETES = new ArrayList<>();
    private static Method getInstance;
    private static boolean reflectionResolved;

    private VoxyReplayCompat() {
    }

    public static void deleteOrDefer(TempFolderProvider.TempFolderType type, UUID playbackId) {
        if (!isVoxySessionActive()) {
            TempFolderProvider.deleteTemp(type, playbackId);
            return;
        }

        synchronized (PENDING_DELETES) {
            PENDING_DELETES.add(new PendingDelete(type, playbackId));
        }
    }

    public static void flushDeferredDeletes() {
        List<PendingDelete> deletes;
        synchronized (PENDING_DELETES) {
            if (PENDING_DELETES.isEmpty()) {
                return;
            }
            deletes = List.copyOf(PENDING_DELETES);
            PENDING_DELETES.clear();
        }

        for (PendingDelete pending : deletes) {
            TempFolderProvider.deleteTemp(pending.type(), pending.playbackId());
        }
    }

    private static boolean isVoxySessionActive() {
        if (!ModList.get().isLoaded("voxy")) {
            return false;
        }
        try {
            if (!reflectionResolved) {
                Class<?> voxyCommon = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
                getInstance = voxyCommon.getMethod("getInstance");
                reflectionResolved = true;
            }
            return getInstance != null && getInstance.invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("Unable to inspect Voxy session; using Flashback's normal temp cleanup",
                    exception);
            return false;
        }
    }

    private record PendingDelete(TempFolderProvider.TempFolderType type, UUID playbackId) {
    }
}
