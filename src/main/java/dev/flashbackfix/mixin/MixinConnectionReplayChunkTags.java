package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.ext.ReplayServerCatchupExt;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guarantees that recorded block-entity tags follow their chunk on the actual network queue. */
@Mixin(Connection.class)
public abstract class MixinConnectionReplayChunkTags {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;"
                    + "Lnet/minecraft/network/PacketSendListener;Z)V",
            at = @At("RETURN"))
    private void flashbackNeoForgeFixed$sendRecordedTagsAfterChunk(
            Packet<?> packet, PacketSendListener listener, boolean flush, CallbackInfo ci) {
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket)
                || !(Minecraft.getInstance().getSingleplayerServer()
                        instanceof ReplayServer replayServer)) {
            return;
        }
        long chunkPos = ChunkPos.asLong(chunkPacket.getX(), chunkPacket.getZ());
        for (var exact : ((ReplayServerCatchupExt) replayServer)
                .flashbackNeoForgeFixed$getChunkBlockEntityUpdates(chunkPos)) {
            ((Connection) (Object) this).send(exact);
        }
    }
}
