package dev.flashbackfix.mixin;

import com.mojang.brigadier.tree.RootCommandNode;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps live-server command registrations out of Flashback's synthetic replay connection. */
@Mixin(Commands.class)
public abstract class MixinCommandsReplay {

    @Inject(method = "sendCommands", at = @At("HEAD"), cancellable = true)
    private void flashbackNeoForgeFixed$sendEmptyReplayCommandTree(
            ServerPlayer player, CallbackInfo ci) {
        if (!(player.server instanceof ReplayServer)) {
            return;
        }

        // A ReplayServer never executes gameplay commands for its viewer. Rebuilding the installed
        // server command tree here only serializes every mod's custom argument type across a local
        // connection that did not run NeoForge's normal registry/login synchronization. In large
        // packs that can produce a command packet whose custom argument data is interpreted with a
        // different layout and make the client decoder run past the end of the frame.
        //
        // Still send a valid empty root so commands retained from the previously joined server or
        // world are cleared. This avoids the malformed data at its source instead of recovering
        // after Netty has already disconnected the replay connection.
        RootCommandNode<SharedSuggestionProvider> root = new RootCommandNode<>();
        player.connection.send(new ClientboundCommandsPacket(root));
        ci.cancel();
    }
}
