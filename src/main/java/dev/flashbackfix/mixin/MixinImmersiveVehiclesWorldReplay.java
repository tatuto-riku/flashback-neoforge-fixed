package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps IV from creating a second live render anchor and simulating vehicles on a replay server. */
@Pseudo
@Mixin(targets = "mcinterface1211.WrapperWorld", remap = false)
public abstract class MixinImmersiveVehiclesWorldReplay {

    /**
     * A normal IV server creates a per-player render forwarder after login and advances its own
     * vehicle simulation. A Flashback ReplayServer is reconstructed from recorded packets and must
     * not create another forwarder or generate unrecorded physics alongside that stream.
     */
    @Inject(method = "onIVWorldTickPre", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$skipLiveReplayPreTick(
            LevelTickEvent.Pre event, CallbackInfo ci) {
        if (!event.getLevel().isClientSide()
                && event.getLevel().getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }

    @Inject(method = "onIVWorldTickPost", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$skipLiveReplayPostTick(
            LevelTickEvent.Post event, CallbackInfo ci) {
        if (!event.getLevel().isClientSide()
                && event.getLevel().getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
