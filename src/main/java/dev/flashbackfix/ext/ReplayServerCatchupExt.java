package dev.flashbackfix.ext;

import net.minecraft.network.protocol.Packet;

public interface ReplayServerCatchupExt {
    void flashbackNeoForgeFixed$queueForNewViewers(Packet<?> packet);

    /**
     * Queues a timeline packet behind the snapshot currently waiting to reach the replay client.
     * Returns true when the packet was queued and must not be sent immediately.
     */
    boolean flashbackNeoForgeFixed$deferUntilSnapshotDelivered(Packet<?> packet);
}
