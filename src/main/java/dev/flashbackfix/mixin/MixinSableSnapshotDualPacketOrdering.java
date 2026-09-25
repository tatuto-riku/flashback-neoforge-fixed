package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.SableCompat;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restores the StartTracking-before-movement dependency lost between TCP and UDP capture. */
@Mixin(ClientboundSableSnapshotDualPacket.class)
public class MixinSableSnapshotDualPacketOrdering {

    @Inject(method = "handleClient(Lnet/minecraft/world/level/Level;"
            + "Ldev/ryanhcode/sable/network/packets/PacketReceiveMode;)V",
            at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$deferUntilTracked(
            Level level, PacketReceiveMode receiveMode, CallbackInfo ci) {
        if (SableCompat.deferReplayMovementUntilTracked(
                (ClientboundSableSnapshotDualPacket) (Object) this, level, receiveMode)) {
            ci.cancel();
        }
    }
}
