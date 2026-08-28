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
        entries.removeIf(entry -> {
            boolean invalid = entry.getValue().getIds().int2ObjectEntrySet().stream()
                    .anyMatch(id -> id.getIntKey() < 0 || id.getValue() == null);
            if (invalid) {
                LOGGER.warn("Not recording structurally invalid NeoForge registry snapshot {}",
                        entry.getKey());
            }
            return invalid;
        });
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
