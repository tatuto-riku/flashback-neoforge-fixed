package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import java.util.Optional;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes Cosmonautics treat Flashback's deliberately partial replay world as non-orbital. */
@Pseudo
@Mixin(targets = "dev.devce.rocketnautics.api.orbit.DeepSpaceHelper", remap = false)
public abstract class MixinRocketnauticsDeepSpaceHelper {

    /**
     * ReplayServer reconstructs only recorded dimensions and therefore has no deep-space level or
     * DeepSpaceData. Returning an empty Optional follows this API's normal "not an orbital world"
     * contract and protects all callers, including breathing, falling, HUD, and future handlers.
     */
    @Inject(method = "getUniverse", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$noLiveUniverseInReplay(
            Level level, CallbackInfoReturnable<Optional<?>> cir) {
        if (level.getServer() instanceof ReplayServer) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
