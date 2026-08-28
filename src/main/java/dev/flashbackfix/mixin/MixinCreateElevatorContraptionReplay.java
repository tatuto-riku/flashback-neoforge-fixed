package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps recorded elevator floor state authoritative instead of rebuilding it from the fake world. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.elevator.ElevatorContraption", remap = false)
public abstract class MixinCreateElevatorContraptionReplay {

    @Inject(method = "tickStorage", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/pulley/PulleyContraption;tickStorage(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;)V",
            shift = At.Shift.AFTER), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$keepRecordedFloorState(CallbackInfo ci) {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
