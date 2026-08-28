package dev.flashbackfix.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SynchedEntityData.class)
public interface AccessorSynchedEntityData {

    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] flashbackNeoForgeFixed$getItemsById();
}
