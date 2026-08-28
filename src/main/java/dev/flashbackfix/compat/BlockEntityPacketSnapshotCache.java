package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Retains the exact last clientbound update packet for each loaded block entity.
 *
 * <p>A chunk snapshot preserves NBT, but it does not preserve the fact that the NBT was read through
 * a client update packet. Mods such as Create deliberately decode extra interpolation fields only in
 * that path. Appending the original packet after the chunk snapshot preserves those client-only fields
 * without knowing the block entity implementation.</p>
 */
public final class BlockEntityPacketSnapshotCache {

    private static final int MAX_ENTRIES = 8192;
    private static final Map<Key, ClientboundBlockEntityDataPacket> LATEST = new LinkedHashMap<>();

    private BlockEntityPacketSnapshotCache() {
    }

    public static synchronized void reset() {
        LATEST.clear();
    }

    public static synchronized void capture(ClientLevel level, ClientboundBlockEntityDataPacket packet) {
        if (level == null || Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            return;
        }
        Key key = new Key(level.dimension(), packet.getPos().immutable());
        LATEST.remove(key);
        LATEST.put(key, packet);
        while (LATEST.size() > MAX_ENTRIES) {
            LATEST.remove(LATEST.keySet().iterator().next());
        }
    }

    public static synchronized void appendSnapshotPackets(
            ClientLevel level, Consumer<Packet<? super ClientGamePacketListener>> consumer) {
        if (level == null || LATEST.isEmpty()) {
            return;
        }
        for (Map.Entry<Key, ClientboundBlockEntityDataPacket> entry : LATEST.entrySet()) {
            Key key = entry.getKey();
            ClientboundBlockEntityDataPacket packet = entry.getValue();
            if (key.dimension() != level.dimension()) {
                continue;
            }
            BlockEntity current = level.getBlockEntity(key.pos());
            if (current == null || current.getType() != packet.getType()) {
                continue;
            }
            consumer.accept(packet);
        }
    }

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }
}
