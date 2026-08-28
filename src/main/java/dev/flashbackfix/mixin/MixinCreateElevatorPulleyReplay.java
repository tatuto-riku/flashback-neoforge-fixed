package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.ReplayCreateElevatorCompat;
import net.minecraft.client.Minecraft;
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

/** Drops velocity accumulated on the opposite side of a replay rewind. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity", remap = false)
public abstract class MixinCreateElevatorPulleyReplay {

    @Shadow
    private float prevSpeed;

    /**
     * The replay server is a packet reconstruction engine, not a live Create world. Letting its
     * pulley tick derives a new offset/target from incomplete ElevatorColumn state and sends that
     * synthetic state after the recorded snapshot, pulling the client back toward floor zero.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$stopReplayServerSimulation(CallbackInfo ci) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level != null && !level.isClientSide() && level.getServer() instanceof ReplayServer) {
            ci.cancel();
            return;
        }
        if (level != null && level.isClientSide()
                && Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer replayServer
                && (replayServer.replayPaused || replayServer.jumpToTick() >= 0
                        || !replayServer.doClientRendering())) {
            ci.cancel();
        }
    }

    @Inject(method = "read", at = @At("TAIL"), require = 0)
    private void flashbackNeoForgeFixed$clearRecordedVelocityOnSeek(
            CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket, CallbackInfo ci) {
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (clientPacket && level != null && level.isClientSide()
                && ReplayCreateElevatorCompat.shouldSnapActuatorState()) {
            this.prevSpeed = 0;
        }
    }
}
