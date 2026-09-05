package dev.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Prevents replay-only players from entering NeoForge's real-server login lifecycle. */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListReplayHooks {

    // Attachment synchronization was added during the NeoForge 21.1 lifecycle. Older supported
    // builds have no such call to suppress, so absence of this invocation must not reject the mixin.
    @WrapWithCondition(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/attachment/AttachmentSync;syncInitialPlayerAttachments(Lnet/minecraft/server/level/ServerPlayer;)V",
            remap = false), require = 0)
    private boolean flashbackNeoForgeFixed$skipFakePlayerAttachmentLogin(ServerPlayer player) {
        return !flashbackNeoForgeFixed$isReplayServerPlayer(player);
    }

    @WrapWithCondition(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;firePlayerLoggedIn(Lnet/minecraft/world/entity/player/Player;)V",
            remap = false))
    private boolean flashbackNeoForgeFixed$skipFakePlayerLoginEvent(Player player) {
        return !(player instanceof ServerPlayer serverPlayer)
                || !flashbackNeoForgeFixed$isReplayServerPlayer(serverPlayer);
    }

    private static boolean flashbackNeoForgeFixed$isReplayServerPlayer(ServerPlayer player) {
        // This includes both entities reconstructed from the recording and the real local
        // ReplayPlayer viewing it. Neither is joining an authoritative gameplay server. Firing
        // normal mod login handlers here makes mods serialize live server state against the replay's
        // synthetic registry set; one such failure disconnects the viewer and stops ReplayServer.
        return player.server instanceof ReplayServer;
    }
}
