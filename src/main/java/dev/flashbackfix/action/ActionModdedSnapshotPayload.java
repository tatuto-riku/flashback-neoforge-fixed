package dev.flashbackfix.action;

import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Modded payload captured for a replay snapshot after transaction completeness was verified.
 * Keeping this identity separate from timeline payloads lets playback quarantine malformed
 * batched snapshots written by older versions without discarding valid live replay actions.
 */
public final class ActionModdedSnapshotPayload implements Action {

    public static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath(
            "flashback_neoforge_fixed", "action/modded_snapshot_payload_v3_optional");
    public static final ActionModdedSnapshotPayload INSTANCE = new ActionModdedSnapshotPayload();

    private ActionModdedSnapshotPayload() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    public static void write(ReplayWriter writer, ActionModdedPayload.EncodedPayload payload) {
        writer.startAction(INSTANCE);
        RegistryFriendlyByteBuf buf = writer.friendlyByteBuf();
        buf.writeEnum(payload.protocol());
        buf.writeResourceLocation(payload.id());
        buf.writeBytes(payload.data());
        writer.finishAction(INSTANCE);
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        ActionModdedPayload.handlePayload(replayServer, buf, false, true);
    }
}
