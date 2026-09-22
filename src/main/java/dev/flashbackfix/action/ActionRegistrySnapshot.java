package dev.flashbackfix.action;

import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.ext.ReplayServerRegistryExt;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores NeoForge's numeric built-in registry mapping before replay game packets are encoded. */
public final class ActionRegistrySnapshot implements Action {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    public static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath(
            "flashback_neoforge_fixed", "action/neoforge_registry_snapshot_v1_optional");
    public static final ActionRegistrySnapshot INSTANCE = new ActionRegistrySnapshot();

    private ActionRegistrySnapshot() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    /** Captures immediately; the replay writer runs asynchronously after packets may mutate state. */
    public static byte[] capture() {
        Map<ResourceLocation, RegistrySnapshot> snapshots =
                RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
        List<Map.Entry<ResourceLocation, RegistrySnapshot>> entries =
                new ArrayList<>(snapshots.entrySet());
        entries.replaceAll(entry -> Map.entry(
                entry.getKey(), sanitize(entry.getKey(), entry.getValue())));
        entries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entries.size());
            for (var entry : entries) {
                buf.writeResourceLocation(entry.getKey());
                RegistrySnapshot.STREAM_CODEC.encode(buf, entry.getValue());
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    public static void write(ReplayWriter writer, byte[] snapshot) {
        writer.startAction(INSTANCE);
        writer.friendlyByteBuf().writeBytes(snapshot);
        writer.finishAction(INSTANCE);
    }

    /**
     * A few mods leave registered keys without a numeric ID. NeoForge represents those entries as
     * {@code -1}; because the snapshot map has the raw ID as its key, they cannot carry a useful
     * packet mapping. Preserve every valid mapping instead of discarding the whole registry.
     */
    private static RegistrySnapshot sanitize(
            ResourceLocation registryName, RegistrySnapshot snapshot) {
        int invalidEntries = (int) snapshot.getIds().int2ObjectEntrySet().stream()
                .filter(entry -> entry.getIntKey() < 0 || entry.getValue() == null)
                .count();
        if (invalidEntries == 0) {
            return snapshot;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int validEntries = snapshot.getIds().size() - invalidEntries;
            buf.writeVarInt(validEntries);
            for (var entry : snapshot.getIds().int2ObjectEntrySet()) {
                if (entry.getIntKey() >= 0 && entry.getValue() != null) {
                    buf.writeVarInt(entry.getIntKey());
                    buf.writeResourceLocation(entry.getValue());
                }
            }
            buf.writeVarInt(snapshot.getAliases().size());
            for (var alias : snapshot.getAliases().entrySet()) {
                buf.writeResourceLocation(alias.getKey());
                buf.writeResourceLocation(alias.getValue());
            }
            RegistrySnapshot sanitized = RegistrySnapshot.STREAM_CODEC.decode(buf);
            LOGGER.warn("Removed {} unmapped entry from NeoForge registry snapshot {}; "
                            + "preserved {} numeric mappings",
                    invalidEntries, registryName, validEntries);
            return sanitized;
        } finally {
            buf.release();
        }
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<ResourceLocation, RegistrySnapshot> snapshots = new LinkedHashMap<>(size);
        for (int index = 0; index < size; index++) {
            ResourceLocation registry = buf.readResourceLocation();
            snapshots.put(registry, RegistrySnapshot.STREAM_CODEC.decode(buf));
        }
        ((ReplayServerRegistryExt) replayServer)
                .flashbackNeoForgeFixed$applyBuiltInRegistrySnapshot(snapshots);
    }
}
