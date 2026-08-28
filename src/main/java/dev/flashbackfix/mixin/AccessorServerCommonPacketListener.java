package dev.flashbackfix.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the connection fault handler a mapping-safe way to identify ReplayServer listeners. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface AccessorServerCommonPacketListener {

    @Accessor("server")
    MinecraftServer flashbackNeoForgeFixed$getServer();
}
