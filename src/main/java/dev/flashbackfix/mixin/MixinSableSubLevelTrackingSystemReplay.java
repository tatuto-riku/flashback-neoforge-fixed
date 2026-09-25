package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents replay-side simulation from competing with the recorded Sable network stream. */
@Mixin(SubLevelTrackingSystem.class)
public class MixinSableSubLevelTrackingSystemReplay {

    @Unique
    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    @Unique
    private boolean flashbackNeoForgeFixed$loggedReplaySuppression;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$suppressSyntheticReplayTracking(
            SubLevelContainer container, CallbackInfo ci) {
        if (container.getLevel().getServer() instanceof ReplayServer) {
            if (!this.flashbackNeoForgeFixed$loggedReplaySuppression) {
                this.flashbackNeoForgeFixed$loggedReplaySuppression = true;
                FLASHBACK_NEOFORGE_FIXED$LOGGER.debug(
                        "Suppressing live Sable tracking output from ReplayServer; using recorded movement only");
            }
            ci.cancel();
        }
    }
}
