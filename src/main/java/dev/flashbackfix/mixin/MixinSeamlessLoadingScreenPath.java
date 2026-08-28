package dev.flashbackfix.mixin;

import java.io.File;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents Seamless Loading Screen 2.2.1 from passing Path.of(null) for a relative screenshot. */
@Pseudo
@Mixin(targets = "com.minenash.seamless_loading_screen.OnLeaveHelper", remap = false)
public class MixinSeamlessLoadingScreenPath {

    @Redirect(method = "takeScreenShot", at = @At(value = "INVOKE",
            target = "Ljava/io/File;getParent()Ljava/lang/String;"), remap = false)
    private static String flashbackNeoForgeFixed$useCurrentDirectoryForRelativeScreenshot(File file) {
        String parent = file.getParent();
        return parent == null ? "." : parent;
    }
}
