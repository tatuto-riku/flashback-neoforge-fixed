package dev.flashbackfix.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Centers server-provided Voxy LOD requests on Flashback's camera instead of its viewer pawn. */
public final class VssReplayCameraCompat {

    private VssReplayCameraCompat() {
    }

    public static int requestCenterX(LocalPlayer player) {
        Vec3 camera = replayCamera(player);
        return Mth.floor(camera.x);
    }

    public static int requestCenterZ(LocalPlayer player) {
        return Mth.floor(replayCamera(player).z);
    }

    private static Vec3 replayCamera(LocalPlayer fallback) {
        if (!ReplayStateCompat.isInReplay()) {
            return fallback.position();
        }
        // Flashback's detached editor camera is implemented by overriding Camera#getPosition.
        // Minecraft#getCameraEntity remains the stationary Replay Viewer pawn.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameRenderer != null
                && minecraft.gameRenderer.getMainCamera() != null
                && minecraft.gameRenderer.getMainCamera().isInitialized()) {
            return minecraft.gameRenderer.getMainCamera().getPosition();
        }
        return fallback.position();
    }

}
