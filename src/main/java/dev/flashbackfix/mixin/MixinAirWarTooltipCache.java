package dev.flashbackfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Function;

/**
 * Serializes access to Create: The Air War's tooltip cache.
 *
 * <p>Minecraft builds creative-tab search entries on worker threads while the render thread may
 * request the same tooltips. Air War 4.67 stores its generated Create descriptions in a plain
 * {@link java.util.HashMap}; concurrent {@code computeIfAbsent} and {@code clear} calls can corrupt
 * that map or throw {@link java.util.ConcurrentModificationException}. Both mutations use the
 * cache itself as their lock so language reloads and tooltip generation cannot overlap.</p>
 */
@Pseudo
@Mixin(targets = "hi.client.tooltip.AirworkTooltipHandler", remap = false)
public abstract class MixinAirWarTooltipCache {

    @Redirect(
            method = "onTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"
            ),
            require = 0
    )
    private static Object flashbackNeoForgeFixed$computeTooltipSafely(
            Map<Object, Object> cache, Object key, Function<Object, Object> factory) {
        synchronized (cache) {
            return cache.computeIfAbsent(key, factory);
        }
    }

    @Redirect(
            method = "refreshCacheIfNeeded",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"),
            require = 0
    )
    private static void flashbackNeoForgeFixed$clearTooltipCacheSafely(Map<?, ?> cache) {
        synchronized (cache) {
            cache.clear();
        }
    }
}
