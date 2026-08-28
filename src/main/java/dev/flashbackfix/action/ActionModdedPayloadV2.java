package dev.flashbackfix.action;

import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Action identity for immutable modded-payload recordings.
 *
 * <p>Flashback keys registered actions by their Java class, so the legacy and v2 formats must use
 * different action classes even though their wire layout and playback implementation are shared.</p>
 */
public final class ActionModdedPayloadV2 implements Action {

    public static final ResourceLocation NAME =
            ResourceLocation.fromNamespaceAndPath("flashback_neoforge_fixed", "action/modded_payload_v2_optional");
    public static final ActionModdedPayloadV2 INSTANCE = new ActionModdedPayloadV2();

    private ActionModdedPayloadV2() {
    }

    @Override
    public ResourceLocation name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        ActionModdedPayload.handlePayload(replayServer, buf, false, false);
    }
}
