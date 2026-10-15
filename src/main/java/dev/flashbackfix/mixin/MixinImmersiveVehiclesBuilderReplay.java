package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ImmersiveVehiclesHandshakeCompat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Delivers an IV snapshot handshake once its vanilla wrapper actually exists on the client. */
@Pseudo
@Mixin(targets = "mcinterface1211.ABuilderEntityBase", remap = false)
public abstract class MixinImmersiveVehiclesBuilderReplay {

    @Inject(method = "baseTick", at = @At("HEAD"), require = 0)
    private void flashbackNeoForgeFixed$applyDeferredReplayHandshake(CallbackInfo ci) {
        Entity builder = (Entity) (Object) this;
        if (builder.level().isClientSide) {
            ImmersiveVehiclesHandshakeCompat.applyPendingHandshake(builder);
        }
    }
}
