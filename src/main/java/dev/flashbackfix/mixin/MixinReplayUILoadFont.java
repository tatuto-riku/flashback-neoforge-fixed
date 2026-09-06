package dev.flashbackfix.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;
import java.util.MissingResourceException;
import java.util.Optional;

@Mixin(ReplayUI.class)
public class MixinReplayUILoadFont {

    /**
     * Flashback loads its bundled UI fonts (e.g. inter-medium.ttf) from the Minecraft
     * ResourceManager during Minecraft init. Under Sinytra Connector the Fabric mod's
     * assets are not always visible to the NeoForge ResourceManager at that point, so the
     * lookup returns empty and Flashback throws MissingResourceException, hard-crashing the
     * game. Fall back to reading the font straight from the Flashback jar on the classpath,
     * which is always available (the asset lives at assets/flashback/<name> inside the jar).
     */
    @Redirect(method = "initFonts",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/editor/ui/ReplayUI;loadFont(Ljava/lang/String;)[B"))
    private static byte[] flashbackNeoForgeFixed$loadFont(String name) {
        Optional<Resource> resource = Minecraft.getInstance()
                .getResourceManager()
                .getResource(ResourceLocation.fromNamespaceAndPath("flashback", name));
        if (resource.isPresent()) {
            try (InputStream is = resource.get().open()) {
                return is.readAllBytes();
            } catch (IOException ignored) {
                // fall through to the classpath fallback below
            }
        }

        try (InputStream is = ReplayUI.class.getResourceAsStream("/assets/flashback/" + name)) {
            if (is == null) {
                throw new MissingResourceException("Missing font: " + name, "Font", "");
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new MissingResourceException("Missing font: " + name, "Font", "");
        }
    }
}
