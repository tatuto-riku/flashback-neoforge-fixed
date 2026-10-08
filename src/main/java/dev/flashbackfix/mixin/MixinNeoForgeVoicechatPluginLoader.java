package dev.flashbackfix.mixin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bridges Flashback's Fabric voicechat entrypoint into Simple Voice Chat's NeoForge loader. */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.intercompatibility.NeoForgeCommonCompatibilityManager",
        remap = false)
public abstract class MixinNeoForgeVoicechatPluginLoader {

    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final String FLASHBACK_NEOFORGE_FIXED$PLUGIN_CLASS =
            "com.moulberry.flashback.compat.simple_voice_chat.SimpleVoiceChatPlugin";
    private static final String FLASHBACK_NEOFORGE_FIXED$PLUGIN_ID = "flashback";

    @Inject(method = "loadPlugins", at = @At("RETURN"), require = 0)
    private void flashbackNeoForgeFixed$loadFlashbackVoicechatPlugin(
            CallbackInfoReturnable<List<?>> cir) {
        List<?> plugins = cir.getReturnValue();
        if (plugins == null || flashbackNeoForgeFixed$containsFlashbackPlugin(plugins)) {
            return;
        }

        try {
            Object plugin = Class.forName(FLASHBACK_NEOFORGE_FIXED$PLUGIN_CLASS)
                    .getDeclaredConstructor()
                    .newInstance();
            @SuppressWarnings({"rawtypes", "unchecked"})
            List rawPlugins = plugins;
            rawPlugins.add(plugin);
            FLASHBACK_NEOFORGE_FIXED$LOGGER.info(
                    "Registered Flashback's Simple Voice Chat plugin on NeoForge");
        } catch (ClassNotFoundException ignored) {
            // Flashback is optional, and this mixin is also harmless on a dedicated server.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            FLASHBACK_NEOFORGE_FIXED$LOGGER.error(
                    "Failed to register Flashback's Simple Voice Chat plugin on NeoForge",
                    exception);
        }
    }

    private static boolean flashbackNeoForgeFixed$containsFlashbackPlugin(List<?> plugins) {
        for (Object plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            try {
                Method getPluginId = plugin.getClass().getMethod("getPluginId");
                if (FLASHBACK_NEOFORGE_FIXED$PLUGIN_ID.equals(getPluginId.invoke(plugin))) {
                    return true;
                }
            } catch (NoSuchMethodException | IllegalAccessException
                    | InvocationTargetException ignored) {
                // Ignore malformed third-party plugins and let Voice Chat report them itself.
            }
        }
        return false;
    }
}
