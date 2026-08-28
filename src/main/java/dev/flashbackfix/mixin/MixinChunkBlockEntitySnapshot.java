package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ClientChunkSnapshotCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public class MixinChunkBlockEntitySnapshot {

    @Redirect(method = "create", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag flashbackNeoForgeFixed$safelySnapshotClientBlockEntity(
            BlockEntity blockEntity, HolderLookup.Provider registries) {
        return ClientChunkSnapshotCompat.getUpdateTag(blockEntity, registries);
    }
}
