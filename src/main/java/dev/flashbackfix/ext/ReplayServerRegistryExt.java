package dev.flashbackfix.ext;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistrySnapshot;

public interface ReplayServerRegistryExt {
    void flashbackNeoForgeFixed$applyBuiltInRegistrySnapshot(
            Map<ResourceLocation, RegistrySnapshot> snapshots);
}
