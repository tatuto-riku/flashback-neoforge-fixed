package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ReplayCreateElevatorCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes replay snapshot offsets discontinuous while retaining normal Create network smoothing. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity", remap = false)
public abstract class MixinCreateLinearActuatorReplay {

    @Shadow
    public float offset;

    @Shadow
    protected float clientOffsetDiff;

    @Shadow
    protected abstract void resetContraptionToOffset();

    @Inject(method = "read", at = @At("TAIL"), require = 0)
    private void flashbackNeoForgeFixed$snapRecordedOffsetOnSeek(
            CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (!clientPacket || level == null || !level.isClientSide()
                || !ReplayCreateElevatorCompat.shouldSnapActuatorState()) {
            return;
        }
        this.offset = compound.getFloat("Offset");
        this.clientOffsetDiff = 0;
        this.resetContraptionToOffset();
    }
}
