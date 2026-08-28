package dev.flashbackfix.compat;

import java.nio.file.Path;

/** Extra replay metadata used by Voxy's native Flashback integration on Fabric. */
public interface VoxyReplayStorageHolder {

    Path flashbackNeoForgeFixed$getVoxyStoragePath();

    void flashbackNeoForgeFixed$setVoxyStoragePath(Path path);
}
