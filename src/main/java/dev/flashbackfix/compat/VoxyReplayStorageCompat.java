package dev.flashbackfix.compat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ports Voxy's Fabric-only Flashback replay-storage integration to NeoForge. */
public final class VoxyReplayStorageCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static boolean replayStorageActive;
    private static boolean loggedMissingStorage;

    private VoxyReplayStorageCompat() {
    }

    /** Called while recording, matching Voxy's Fabric MixinFlashbackRecorder behavior. */
    public static void captureRecordingStorage(VoxyReplayStorageHolder metadata) {
        if (!ModList.get().isLoaded("voxy")) {
            return;
        }
        Path path = activeClientStoragePath();
        if (path != null) {
            metadata.flashbackNeoForgeFixed$setVoxyStoragePath(path);
        }
    }

    /** Called from Voxy's NeoForge PlatformUtilImpl before VoxyClientInstance is constructed. */
    public static Path resolveReplayStoragePath() {
        // Voxy creates its client instance while Flashback is still finishing the replay join. The
        // public isInReplay flag can lag the already-created ReplayServer by one client tick.
        if (!ReplayStateCompat.isInReplay() && !hasReplayServer()) {
            return null;
        }

        Path path = metadataStoragePath();
        if (!isStorageBase(path)) {
            path = discoverLegacyStoragePath();
        }

        if (isStorageBase(path)) {
            path = path.toAbsolutePath().normalize();
            replayStorageActive = true;
            return path;
        }

        replayStorageActive = false;
        if (!loggedMissingStorage) {
            loggedMissingStorage = true;
            LOGGER.warn("This replay has no usable voxy_storage_path; using live VSS replay fallback");
        }
        return null;
    }

    public static boolean isReplayStorageActive() {
        return replayStorageActive && ReplayStateCompat.isInReplay();
    }

    public static void clearReplayStorageState() {
        replayStorageActive = false;
    }

    private static Path activeClientStoragePath() {
        try {
            Class<?> voxyCommon = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Object instance = voxyCommon.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return null;
            }
            Method getter = instance.getClass().getMethod("getStorageBasePath");
            Object value = getter.invoke(instance);
            return value instanceof Path path ? path.toAbsolutePath().normalize() : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Path metadataStoragePath() {
        try {
            Class<?> flashback = Class.forName("com.moulberry.flashback.Flashback");
            Object replayServer = flashback.getMethod("getReplayServer").invoke(null);
            if (replayServer == null) {
                return null;
            }
            Object metadata = replayServer.getClass().getMethod("getMetadata").invoke(replayServer);
            if (metadata instanceof VoxyReplayStorageHolder holder) {
                return holder.flashbackNeoForgeFixed$getVoxyStoragePath();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }

    private static boolean hasReplayServer() {
        try {
            Class<?> flashback = Class.forName("com.moulberry.flashback.Flashback");
            return flashback.getMethod("getReplayServer").invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * NeoForge Voxy 0.2.15 did not write the metadata property. For recordings made on this machine,
     * match the dimension database id created in Flashback's temporary replay save to the same id in
     * Voxy's multiplayer cache. This is deliberately identity-based, never "most recent world".
     */
    private static Path discoverLegacyStoragePath() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path replayServers = gameDir.resolve("flashback").resolve("temp").resolve("server");
        Path savedVoxyWorlds = gameDir.resolve(".voxy").resolve("saves");
        if (!Files.isDirectory(replayServers) || !Files.isDirectory(savedVoxyWorlds)) {
            return null;
        }

        Set<String> replayDimensionIds = new HashSet<>();
        try (Stream<Path> paths = Files.walk(replayServers, 8)) {
            paths.filter(Files::isDirectory)
                    .filter(VoxyReplayStorageCompat::isDimensionStorage)
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("voxy"))
                    .map(path -> path.getFileName().toString())
                    .forEach(replayDimensionIds::add);
        } catch (Exception ignored) {
            return null;
        }
        if (replayDimensionIds.isEmpty()) {
            return null;
        }

        try (Stream<Path> paths = Files.walk(savedVoxyWorlds, 4)) {
            return paths.filter(Files::isDirectory)
                    .filter(VoxyReplayStorageCompat::isDimensionStorage)
                    .filter(path -> replayDimensionIds.contains(path.getFileName().toString()))
                    .map(Path::getParent)
                    .filter(VoxyReplayStorageCompat::isStorageBase)
                    .max(Comparator.comparing(VoxyReplayStorageCompat::lastModifiedOrEpoch))
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isDimensionStorage(Path path) {
        return path != null && Files.isDirectory(path.resolve("storage"));
    }

    private static boolean isStorageBase(Path path) {
        if (path == null || !Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> children = Files.list(path)) {
            return children.anyMatch(VoxyReplayStorageCompat::isDimensionStorage);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static FileTime lastModifiedOrEpoch(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (Exception ignored) {
            return FileTime.fromMillis(0L);
        }
    }
}
