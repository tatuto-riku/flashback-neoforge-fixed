package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops poses from a later interpolation timeline when a replay snapshot moves time backwards. */
@Mixin(SubLevelSnapshotInterpolator.class)
public class MixinSubLevelSnapshotInterpolator {

    @Inject(method = "receiveSnapshot", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$discardFutureTimeline(int gameTick, Pose3dc pose, CallbackInfo ci) {
        if (!(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            return;
        }

        var buffer = ((SubLevelSnapshotInterpolator) (Object) this).buffer;
        synchronized (buffer) {
            if (!buffer.isEmpty() && gameTick < buffer.getLast().gameTick()) {
                buffer.clear();
            }
        }
    }
}
