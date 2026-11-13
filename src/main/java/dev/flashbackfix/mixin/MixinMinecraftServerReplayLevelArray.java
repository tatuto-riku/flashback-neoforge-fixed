package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge ticks levels from a cached array that is only rebuilt by {@code markWorldsDirty()}.
 * Flashback targets Fabric and edits {@code levels} directly whenever the replay changes dimension
 * or reloads registries, so on NeoForge a level created mid-replay is never ticked. Its entity
 * tracker then stops sending head rotation and entity data (skin layers included) for the recorded
 * player; only Flashback's own teleport packets still arrive, which looks like a frozen head and a
 * broken skin. Revalidate the cache before every replay tick instead of patching each mutation site.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerReplayLevelArray {

    @Shadow
    @Final
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Shadow
    private ServerLevel[] worldArray;

    @Shadow
    public abstract void markWorldsDirty();

    @Inject(method = "tickChildren", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$refreshReplayLevelArray(
            BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if ((Object) this instanceof ReplayServer && !this.flashbackNeoForgeFixed$isLevelArrayCurrent()) {
            this.markWorldsDirty();
        }
    }

    @Unique
    private boolean flashbackNeoForgeFixed$isLevelArrayCurrent() {
        ServerLevel[] cached = this.worldArray;
        if (cached == null || cached.length != this.levels.size()) {
            return false;
        }
        for (ServerLevel level : cached) {
            if (this.levels.get(level.dimension()) != level) {
                return false;
            }
        }
        return true;
    }
}
