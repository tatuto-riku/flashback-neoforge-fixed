package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayConfigurationPacketHandler;
import com.moulberry.flashback.playback.ReplayServer;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes replayed configuration tags tolerant of server-only custom registries. */
@Mixin(ReplayConfigurationPacketHandler.class)
public abstract class MixinReplayConfigurationPacketHandler {

    @Unique
    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");

    @Shadow
    @Final
    private ReplayServer replayServer;

    @Shadow
    private Map<ResourceKey<? extends Registry<?>>,
            TagNetworkSerialization.NetworkPayload> pendingTags;

    /**
     * Flashback deliberately treats a failed dynamic-registry rebuild as recoverable, but then uses
     * registryOrThrow for the following tag packet. A recording made on a server with a server-only
     * synced registry therefore crashes even though the registry failure itself was already handled.
     * Such a registry has no codec in this installation and cannot be introduced by the pending
     * registry-data packets, so remove only its unusable tags before Flashback processes the batch.
     */
    @Inject(method = "flushPendingConfiguration", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$discardTagsForUnavailableRegistries(CallbackInfo ci) {
        if (this.pendingTags == null || this.pendingTags.isEmpty()) {
            return;
        }

        this.pendingTags.entrySet().removeIf(entry -> {
            ResourceKey<? extends Registry<?>> registryKey = entry.getKey();
            if (this.replayServer.registryAccess().registry(registryKey).isPresent()) {
                return false;
            }
            FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                    "Ignoring replay tags for unavailable server registry {}",
                    registryKey.location());
            return true;
        });
    }
}
