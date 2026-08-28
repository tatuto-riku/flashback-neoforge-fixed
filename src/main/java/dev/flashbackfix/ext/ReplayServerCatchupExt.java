package dev.flashbackfix.ext;

import net.minecraft.network.protocol.Packet;

public interface ReplayServerCatchupExt {
    void flashbackNeoForgeFixed$queueForNewViewers(Packet<?> packet);
}
