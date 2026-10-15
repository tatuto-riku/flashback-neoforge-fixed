package dev.flashbackfix.mixin;

import com.moulberry.flashback.io.ReplayReader;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes block-entity state only after every action in a replay snapshot has been applied. */
@Mixin(ReplayReader.class)
public abstract class MixinReplayReaderSnapshotBlockEntities {

    @Inject(method = "handleSnapshot", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$finishSnapshotBlockEntityUpdates(
            ReplayServer replayServer, CallbackInfo ci) {
        ((ReplayServerCatchupExt) replayServer)
                .flashbackNeoForgeFixed$finishSnapshotBlockEntityUpdates();
    }
}
