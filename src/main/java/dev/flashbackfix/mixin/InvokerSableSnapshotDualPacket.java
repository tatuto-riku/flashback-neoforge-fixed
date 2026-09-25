package dev.flashbackfix.mixin;

import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundSableSnapshotDualPacket.class)
public interface InvokerSableSnapshotDualPacket {

    @Invoker("handleClient")
    void flashbackNeoForgeFixed$handleClient(Level level, PacketReceiveMode receiveMode);
}
