package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Create from physically re-simulating already-recorded actors on the replay client.
 * The replay stream is the ground truth; running client collision again can place a jumping or
 * flying remote player back on an elevator between consecutive recorded movement packets.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ContraptionCollider", remap = false)
public abstract class MixinCreateContraptionColliderReplay {

    @Inject(method = "collideEntities", at = @At("HEAD"), cancellable = true, require = 0)
    private static void flashbackNeoForgeFixed$skipReplayClientCollision(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread() && minecraft.getSingleplayerServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
