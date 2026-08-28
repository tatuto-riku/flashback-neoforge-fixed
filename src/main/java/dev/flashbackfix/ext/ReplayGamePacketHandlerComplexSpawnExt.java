package dev.flashbackfix.ext;

import net.minecraft.world.entity.Entity;

public interface ReplayGamePacketHandlerComplexSpawnExt {
    Entity flashbackNeoForgeFixed$applyComplexSpawnData(int entityId, byte[] data);
}
