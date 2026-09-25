package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.SableCompat;
import dev.flashbackfix.ext.SableInterpolationStateExt;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Sable's full sub-level sync safe to replay more than once.
 *
 * Flashback reapplies a chunk snapshot when opening a replay and whenever playback seeks back across
 * a snapshot boundary. Vanilla state is reconstructed by overwriting the replay server, but Sable's
 * client-only sub-level container survives that operation. Its normal start-tracking handler assumes
 * the plot is new and throws if the same snapshot tries to allocate it again. Removing the old client
 * copy first gives the replayed Start -> chunks -> Finalize sequence the same clean state it had when
 * originally received.
 */
@Mixin(ClientboundStartTrackingSubLevelPacket.class)
public class MixinSableStartTrackingSubLevelPacket {

    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    @Inject(method = "handle", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$replaceExistingPlotDuringReplay(CallbackInfo ci) {
        if (!(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            return;
        }

        // Normally the ordered marker has already performed this reset. Retry only the generation
        // actually observed by the client (rather than the server thread's newest generation) in
        // case the level/container did not exist yet when that marker arrived.
        SableCompat.ensureReceivedReplaySnapshotReset();

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        long plotCoordinate = ((ClientboundStartTrackingSubLevelPacket) (Object) this).plotCoordinate();
        int plotX = ChunkPos.getX(plotCoordinate);
        int plotZ = ChunkPos.getZ(plotCoordinate);
        SubLevel existing = container.getSubLevel(plotX, plotZ);
        ClientboundStartTrackingSubLevelPacket packet =
                (ClientboundStartTrackingSubLevelPacket) (Object) this;
        FLASHBACK_NEOFORGE_FIXED$LOGGER.debug(
                "Sable replay StartTracking generation={} plot=({}, {}) id={} tick={} replacing={}",
                SableCompat.receivedReplaySnapshotGeneration(), plotX, plotZ,
                packet.subLevelID(), packet.gameTick(), existing != null);
        if (existing != null) {
            ((SableInterpolationStateExt) container.getInterpolation())
                    .flashbackNeoForgeFixed$resetForReplaySnapshot();
            container.removeSubLevel(existing, SubLevelRemovalReason.UNLOADED);
        }
    }

    @Inject(method = "handle", at = @At("TAIL"))
    private void flashbackNeoForgeFixed$applyMovementDeferredUntilTracking(CallbackInfo ci) {
        if (!(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            SableCompat.flushDeferredReplayMovements(level);
        }
    }
}
