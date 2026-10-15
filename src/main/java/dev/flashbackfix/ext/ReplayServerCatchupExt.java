package dev.flashbackfix.ext;

import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;

public interface ReplayServerCatchupExt {
    void flashbackNeoForgeFixed$queueForNewViewers(Packet<?> packet);

    /**
     * Queues a timeline packet behind the snapshot currently waiting to reach the replay client.
     * Returns true when the packet was queued and must not be sent immediately.
     */
    boolean flashbackNeoForgeFixed$deferUntilSnapshotDelivered(Packet<?> packet);

    /** Remembers the last authoritative recorded client tag for each block entity. */
    void flashbackNeoForgeFixed$cacheChunkBlockEntityUpdate(
            ClientboundBlockEntityDataPacket packet);

    /** Returns the recorded tags which must follow an outbound chunk packet on the same connection. */
    List<ClientboundBlockEntityDataPacket> flashbackNeoForgeFixed$getChunkBlockEntityUpdates(
            long chunkPos);

    /** Marks the deduplicated block-entity image complete after ReplayReader finishes a snapshot. */
    void flashbackNeoForgeFixed$finishSnapshotBlockEntityUpdates();
}
