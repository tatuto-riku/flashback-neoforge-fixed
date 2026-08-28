package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a fake replay world from replacing recorded floor names with an empty live response. */
@Pseudo
@Mixin(targets =
        "com.simibubi.create.content.contraptions.elevator.ElevatorFloorListPacket$RequestFloorList",
        remap = false)
public abstract class MixinCreateElevatorFloorRequestReplay {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$keepRecordedFloorResponse(ServerPlayer sender, CallbackInfo ci) {
        if (sender.getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
