package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Create: Cosmonautics' persistent universe simulation out of Flashback's partial server.
 *
 * <p>A ReplayServer intentionally reconstructs only the dimension present in the recording.
 * Cosmonautics 26.08.307 nevertheless handles every global server tick by dereferencing its
 * {@code rocketnautics:deep_space} level. Opening a replay made outside that dimension therefore
 * passes {@code null} to DeepSpaceData and crashes before playback starts. Universe advancement and
 * its player persistence hooks are live-server state, not recorded visual state, so they must not
 * run for a replay viewer.</p>
 */
@Pseudo
@Mixin(targets = "dev.devce.rocketnautics.content.orbit.DeepSpaceData", remap = false)
public abstract class MixinRocketnauticsDeepSpaceData {

    @Inject(method = "advanceUniverse", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$skipUniverseTickInReplay(
            ServerTickEvent.Post event, CallbackInfo ci) {
        if (event.getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$skipReplayViewerLogin(
            PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        if (event.getEntity().getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerLogout", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$skipReplayViewerLogout(
            PlayerEvent.PlayerLoggedOutEvent event, CallbackInfo ci) {
        if (event.getEntity().getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
