package dev.flashbackfix.ext;

import net.minecraft.nbt.CompoundTag;

public interface ReplayGamePacketHandlerPowerGridExt {
    void flashbackNeoForgeFixed$applyPowerGridEntityData(int entityId, CompoundTag data);

    void flashbackNeoForgeFixed$resetPowerGridEntityData();
}
