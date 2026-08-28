package dev.flashbackfix.mixin;

import java.io.InputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Makes Voxy's optional classpath package scan valid on NeoForge's module class loader. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.config.Serialization", remap = false)
public abstract class MixinVoxySerialization {

    @Redirect(method = "collectAllClasses(Ljava/lang/String;)Ljava/util/List;",
            at = @At(value = "INVOKE",
                    target = "Ljava/lang/ClassLoader;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"),
            require = 0)
    private static InputStream flashbackNeoForgeFixed$emptyMissingPackageResource(
            ClassLoader classLoader, String name) {
        InputStream stream = classLoader.getResourceAsStream(name);
        return stream != null ? stream : InputStream.nullInputStream();
    }
}
