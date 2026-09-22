package dev.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flashbackfix.compat.ClientChunkSnapshotCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo",
        priority = 2000)
public class MixinChunkBlockEntitySnapshot {

    // This operation is also guarded by the legacy Create: Aeronautics Flashback mod. A high-priority
    // WrapOperation composes with other wrappers, while require = 0 remains safe when an older
    // Redirect has already claimed the call.
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"),
            require = 0)
    private static CompoundTag flashbackNeoForgeFixed$safelySnapshotClientBlockEntity(
            BlockEntity blockEntity,
            HolderLookup.Provider registries,
            Operation<CompoundTag> original) {
        return ClientChunkSnapshotCompat.getUpdateTag(
                blockEntity, () -> original.call(blockEntity, registries));
    }
}
