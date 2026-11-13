package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Treats Cobblemon's in-process replay connection like an ordinary integrated-server client. */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.data.CobblemonDataProvider", remap = false)
public abstract class MixinCobblemonDataProviderReplay {

    // Cobblemon skips its full data-registry transaction for a vanilla memory connection because
    // both sides already share the same loaded registries. Flashback's ReplayServer is also in the
    // same process, but its synthetic channel is not marked as a memory connection. Without this
    // equivalence Cobblemon needlessly serializes every species/type registry again while a replay
    // opens. Large extensions such as Lost Lore can then exceed/fail custom-payload encoding. Keep
    // the rest of sync() intact so DATA_SYNCHRONIZED and normal player initialization still run.
    @Redirect(method = "sync", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;isMemoryConnection()Z"), require = 0)
    private boolean flashbackNeoForgeFixed$skipReplayRegistryResync(Connection connection) {
        return connection.isMemoryConnection()
                || Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer;
    }
}
