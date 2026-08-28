package dev.flashbackfix.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flashback's replay playback drives a real ServerPlayer over a real Connection, but it builds that
 * connection itself instead of running it through Minecraft's normal login/configuration handshake.
 * On Fabric that's fine, but NeoForge's networking layer refuses to send a mod's custom payloads to a
 * client until that handshake has negotiated which channels the client supports (see
 * NetworkRegistry#checkPacket). Since Flashback's fake connection never negotiates anything, any mod
 * that sends its own packets during playback - Create's train/contraption sync, for example, but really
 * any mod using NeoForge networking - throws UnsupportedOperationException and kills the replay server.
 * <p>
 * The replay server and the player watching it are always the same NeoForge instance talking to itself,
 * so there both sides trivially support the exact same set of channels. configureMockConnection is
 * NeoForge's own helper for exactly that "both ends are us" case (it's what gametests use), so we just
 * apply it to Flashback's connection before anything gets a chance to send a packet over it.
 */
@Mixin(targets = "com.moulberry.flashback.playback.ReplayServer$1")
public class MixinReplayServerPlayerList {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$negotiateChannels(Connection connection, ServerPlayer serverPlayer,
            CommonListenerCookie commonListenerCookie, CallbackInfo ci) {
        NetworkRegistry.configureMockConnection(connection);
    }
}
