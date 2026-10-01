package dev.flashbackfix.compat;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Defines payloads whose authoritative value belongs to the current replay connection. */
public final class ReplayPayloadPolicy {

    private static final Set<ResourceLocation> CONNECTION_SCOPED = Set.of(
            id("rctmod", "player_state"),
            id("rctmod", "trainer_manager"),
            id("rctmod", "file_download"));

    /**
     * One-shot client UI responses are effects of the original player's interaction, not replay
     * state. Replaying one can open a screen (or a confirmation dialog) when the viewer seeks past
     * the packet. Keep the complete known Sable Cleanup clientbound UI protocol here rather than
     * excluding its namespace, so a future persistent-state packet is not silently discarded.
     */
    private static final Set<ResourceLocation> TRANSIENT_CLIENT_EFFECTS = Set.of(
            id("sablecleanup", "open_sable_list"),
            id("sablecleanup", "diagram_too_large"),
            id("sablecleanup", "diagram_data"),
            id("sablecleanup", "disassemble_tilt_warning"),
            id("sablecleanup", "schematic_bearing_warning"),
            id("sablecleanup", "schematic_list_data"),
            id("sablecleanup", "schematic_preview_data"));

    private ReplayPayloadPolicy() {
    }

    /**
     * These are login/session transactions, not recorded world state. The mod running on the
     * ReplayServer sends a fresh authoritative transaction when the replay viewer joins. Replaying
     * the original client's cached transaction can interleave two batches in a client-global
     * accumulator and corrupt both.
     */
    public static boolean isConnectionScoped(ResourceLocation id) {
        return CONNECTION_SCOPED.contains(id);
    }

    /** True when the payload must never be persisted or delivered during replay playback. */
    public static boolean isTransientClientEffect(ResourceLocation id) {
        return TRANSIENT_CLIENT_EFFECTS.contains(id);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
