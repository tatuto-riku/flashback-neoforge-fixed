package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ImmersiveVehiclesHandshakeCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes an IV state handshake when Flashback changed the reconstructed wrapper UUID. */
@Pseudo
@Mixin(targets = "mcinterface1211.PacketEntityCSHandshakeServer", remap = false)
public abstract class MixinImmersiveVehiclesHandshakeReplay {

    @Inject(method = "handle", at = @At("TAIL"), require = 0)
    private void flashbackNeoForgeFixed$finishReplayHandshake(CallbackInfo ci) {
        ImmersiveVehiclesHandshakeCompat.finishReplayHandshake(this);
    }
}
