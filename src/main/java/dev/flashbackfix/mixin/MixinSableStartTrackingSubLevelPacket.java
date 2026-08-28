package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.ext.SableInterpolationStateExt;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
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

    @Inject(method = "handle", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$replaceExistingPlotDuringReplay(CallbackInfo ci) {
        if (!(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            return;
        }

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
        if (existing != null) {
            ((SableInterpolationStateExt) container.getInterpolation())
                    .flashbackNeoForgeFixed$resetForReplaySnapshot();
            container.removeSubLevel(existing, SubLevelRemovalReason.UNLOADED);
        }
    }
}
