package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import dev.flashbackfix.ext.ReplayServerRegistryExt;
import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Restores the best available NeoForge numeric registry mapping for a replay. */
public final class ReplayRegistryCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final Set<String> BASE_NAMESPACES = Set.of("minecraft", "brigadier");
    private static final Method APPLY_SNAPSHOT = findApplySnapshot();

    private ReplayRegistryCompat() {
    }

    private static Method findApplySnapshot() {
        try {
            return RegistryManager.class.getMethod("applySnapshot", Map.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
            try {
                // NeoForge 21.1.172 and earlier 21.1 builds also expose allowMissing.
                return RegistryManager.class.getMethod(
                        "applySnapshot", Map.class, boolean.class, boolean.class);
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(
                        "Unsupported NeoForge RegistryManager.applySnapshot signature", exception);
            }
        }
    }

    /** Applies a replay snapshot across both NeoForge 21.1 registry API variants. */
    @SuppressWarnings("unchecked")
    public static Set<ResourceKey<?>> applySnapshot(
            Map<ResourceLocation, RegistrySnapshot> snapshots) {
        try {
            Object result = APPLY_SNAPSHOT.getParameterCount() == 2
                    ? APPLY_SNAPSHOT.invoke(null, snapshots, false)
                    : APPLY_SNAPSHOT.invoke(null, snapshots, false, false);
            return (Set<ResourceKey<?>>) result;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access NeoForge registry snapshot API", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("NeoForge registry snapshot application failed", cause);
        }
    }

    /**
     * Recordings made before ActionRegistrySnapshot retain namespace encounter order in Flashback's
     * metadata. NeoForge normally registers one mod namespace as a group, so that order can recover
     * old raw IDs when the entries themselves are still installed. It is deliberately only a legacy
     * fallback; new recordings carry every exact ID.
     */
    public static void applyLegacyNamespaceOrder(ReplayServer replayServer) {
        FlashbackMeta metadata = replayServer.getMetadata();
        if (metadata == null || metadata.namespacesForRegistries == null) {
            return;
        }

        Map<ResourceLocation, RegistrySnapshot> current =
                RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
        Map<ResourceLocation, RegistrySnapshot> reordered = new LinkedHashMap<>();

        for (var metadataEntry : metadata.namespacesForRegistries.entrySet()) {
            ResourceLocation registryName = ResourceLocation.tryParse(metadataEntry.getKey());
            RegistrySnapshot currentSnapshot = registryName == null ? null : current.get(registryName);
            Registry<?> registry = registryName == null ? null : BuiltInRegistries.REGISTRY.get(registryName);
            if (currentSnapshot == null || registry == null) {
                continue;
            }

            List<ResourceLocation> currentIds = new ArrayList<>(currentSnapshot.getIds().values());
            LinkedHashSet<String> currentOrder = namespaceOrder(currentIds);
            LinkedHashSet<String> replayOrder = metadataEntry.getValue();
            if (new ArrayList<>(currentOrder).equals(new ArrayList<>(replayOrder))) {
                continue;
            }
            if (!currentOrder.containsAll(replayOrder)) {
                LinkedHashSet<String> missing = new LinkedHashSet<>(replayOrder);
                missing.removeAll(currentOrder);
                LOGGER.warn("Cannot reconstruct replay registry {} because namespaces are missing: {}",
                        registryName, missing);
                continue;
            }

            List<ResourceLocation> desired = new ArrayList<>(currentIds.size());
            appendNamespaces(desired, currentIds, BASE_NAMESPACES);
            for (String namespace : replayOrder) {
                appendNamespace(desired, currentIds, namespace);
            }
            for (String namespace : currentOrder) {
                if (!replayOrder.contains(namespace)) {
                    appendNamespace(desired, currentIds, namespace);
                }
            }
            // Retain any unusual entries that did not fit a namespace group exactly once.
            for (ResourceLocation id : currentIds) {
                if (!desired.contains(id)) {
                    desired.add(id);
                }
            }

            reordered.put(registryName, createSnapshot(desired, currentSnapshot));
            LOGGER.warn("Reconstructed legacy replay registry {} namespace order: {} -> {}",
                    registryName, currentOrder, replayOrder);
        }

        if (!reordered.isEmpty()) {
            ((ReplayServerRegistryExt) replayServer)
                    .flashbackNeoForgeFixed$applyBuiltInRegistrySnapshot(reordered);
        }
    }

    private static LinkedHashSet<String> namespaceOrder(List<ResourceLocation> ids) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ResourceLocation id : ids) {
            if (!BASE_NAMESPACES.contains(id.getNamespace())) {
                result.add(id.getNamespace());
            }
        }
        return result;
    }

    private static void appendNamespaces(List<ResourceLocation> output,
            List<ResourceLocation> input, Set<String> namespaces) {
        for (ResourceLocation id : input) {
            if (namespaces.contains(id.getNamespace())) {
                output.add(id);
            }
        }
    }

    private static void appendNamespace(List<ResourceLocation> output,
            List<ResourceLocation> input, String namespace) {
        for (ResourceLocation id : input) {
            if (id.getNamespace().equals(namespace)) {
                output.add(id);
            }
        }
    }

    private static RegistrySnapshot createSnapshot(
            List<ResourceLocation> ids, RegistrySnapshot source) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(ids.size());
            for (int index = 0; index < ids.size(); index++) {
                buf.writeVarInt(index);
                buf.writeResourceLocation(ids.get(index));
            }
            buf.writeVarInt(source.getAliases().size());
            for (var alias : source.getAliases().entrySet()) {
                buf.writeResourceLocation(alias.getKey());
                buf.writeResourceLocation(alias.getValue());
            }
            return RegistrySnapshot.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }
}
