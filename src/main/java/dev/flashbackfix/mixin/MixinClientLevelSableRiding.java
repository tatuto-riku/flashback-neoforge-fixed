package dev.flashbackfix.mixin;

import dev.flashbackfix.FlashbackNeoForgeFixed;
import dev.flashbackfix.compat.ReplayAuthoritativeEntityCompat;
import dev.flashbackfix.compat.ReplayCreateElevatorCompat;
import dev.flashbackfix.compat.ReplayDeferredEntityPayloads;
import dev.flashbackfix.compat.SableReplayRidingCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reapplies Sable's native passenger transform after Flashback's replay position updates. */
@Mixin(ClientLevel.class)
public class MixinClientLevelSableRiding {

    @Inject(method = "tickEntities", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$reconcileSableRiding(CallbackInfo ci) {
        // ClientLevel.tickEntities() ticks block entities before returning. Reapply the recorded
        // entity result afterward so a modded controller cannot replace replay data with simulation.
        ClientLevel level = (ClientLevel) (Object) this;
        ReplayAuthoritativeEntityCompat.reconcile(level);
        ReplayDeferredEntityPayloads.flush(level);
        ReplayCreateElevatorCompat.tick(level);
        if (FlashbackNeoForgeFixed.isSableLoaded) {
            SableReplayRidingCompat.reconcile(level);
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$resetSableRiding(CallbackInfo ci) {
        ReplayAuthoritativeEntityCompat.reset();
        ReplayCreateElevatorCompat.reset();
        ReplayDeferredEntityPayloads.reset();
        if (FlashbackNeoForgeFixed.isSableLoaded) {
            SableReplayRidingCompat.reset();
        }
    }
}
