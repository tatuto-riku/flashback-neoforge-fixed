package dev.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Prevents synthetic replay players from generating missing terrain for location advancements. */
@Mixin(ServerPlayer.class)
public class MixinServerPlayerReplayAdvancements {

    @WrapOperation(method = "doTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/advancements/critereon/PlayerTrigger;"
                    + "trigger(Lnet/minecraft/server/level/ServerPlayer;)V"), require = 0)
    private void flashbackNeoForgeFixed$skipReplayAdvancementChecks(
            PlayerTrigger trigger, ServerPlayer player, Operation<Void> original) {
        if (!(player.server instanceof ReplayServer)) {
            original.call(trigger, player);
        }
    }
}
