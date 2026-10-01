package dev.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears an editor mouse grab that can survive the first replay's loading screen. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftReplayInputRecovery {

    @Unique
    private boolean flashbackNeoForgeFixed$replayInputReady;

    @Inject(method = "runTick", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$recoverReplayInput(
            boolean renderLevel, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        boolean ready = minecraft.getSingleplayerServer() instanceof ReplayServer
                && ReplayUI.isActive()
                && minecraft.level != null
                && minecraft.player != null
                && minecraft.screen == null
                && minecraft.getOverlay() == null;

        if (ready && !this.flashbackNeoForgeFixed$replayInputReady) {
            // F1 fixed the symptom because ReplayUI's active -> inactive transition calls ungrab().
            // Do the same once the initial loading screen has actually released input. This also
            // makes returning from an ordinary replay-side screen deterministic.
            ReplayUI.imguiGlfw.ungrab();
            minecraft.mouseHandler.setIgnoreFirstMove();
        }
        this.flashbackNeoForgeFixed$replayInputReady = ready;
    }
}
