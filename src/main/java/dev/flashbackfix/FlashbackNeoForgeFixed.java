package dev.flashbackfix;

import com.moulberry.flashback.action.ActionRegistry;
import dev.flashbackfix.action.ActionForwardedGamePacket;
import dev.flashbackfix.action.ActionForwardedModdedPayload;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.flashbackfix.action.ActionModdedPayloadV2;
import dev.flashbackfix.action.ActionModdedSnapshotPayload;
import dev.flashbackfix.action.ActionRegistrySnapshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(FlashbackNeoForgeFixed.MODID)
public class FlashbackNeoForgeFixed {

    public static final String MODID = "flashback_neoforge_fixed";

    public static boolean isSableLoaded = false;

    public FlashbackNeoForgeFixed() {
        // Flashback (and its action system) only exists on the client; guard in case this mod is
        // ever present without it, or on a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT && isFlashbackLoaded()) {
            ActionRegistry.register(ActionModdedPayload.INSTANCE);
            ActionRegistry.register(ActionModdedPayloadV2.INSTANCE);
            ActionRegistry.register(ActionModdedSnapshotPayload.INSTANCE);
            ActionRegistry.register(ActionRegistrySnapshot.INSTANCE);
            ActionRegistry.register(ActionForwardedGamePacket.INSTANCE);
            ActionRegistry.register(ActionForwardedModdedPayload.INSTANCE);
        }

        isSableLoaded = isClassPresent("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
    }

    private static boolean isFlashbackLoaded() {
        return isClassPresent("com.moulberry.flashback.action.ActionRegistry");
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
