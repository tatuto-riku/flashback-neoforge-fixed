package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ReplayStateCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes Immersive Vehicles' single render anchor follow the replay viewer. */
@Pseudo
@Mixin(targets = "mcinterface1211.BuilderEntityRenderForwarder", remap = false)
public abstract class MixinImmersiveVehiclesRenderForwarderReplay {

    @Shadow(remap = false)
    protected Player playerFollowing;

    /**
     * IV does not render vehicles through their vanilla wrapper entities. Instead, one invisible
     * entity follows the local player and its renderer draws every IV object in the client world.
     * The forwarder's saved NBT contains the player UUID from the recording. In a replay that UUID
     * can resolve to a recorded player entity rather than Minecraft's actual replay viewer, making
     * IV's identity check in the renderer fail and leaving all vehicles invisible.
     *
     * <p>Retarget the recorded anchor before IV's own tick examines the stale UUID. This is scoped
     * to a client replay, so normal worlds retain IV's original ownership and lifecycle.</p>
     */
    @Inject(method = "baseTick", at = @At("HEAD"), require = 0)
    private void flashbackNeoForgeFixed$followReplayViewer(CallbackInfo ci) {
        Entity forwarder = (Entity) (Object) this;
        if (!forwarder.level().isClientSide || !ReplayStateCompat.isInReplay()) {
            return;
        }

        LocalPlayer viewer = Minecraft.getInstance().player;
        if (viewer == null || viewer.level() != forwarder.level()) {
            return;
        }

        if (this.playerFollowing != viewer) {
            this.playerFollowing = viewer;
        }
    }
}
