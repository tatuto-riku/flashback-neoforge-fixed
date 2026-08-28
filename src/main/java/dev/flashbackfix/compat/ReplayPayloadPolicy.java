package dev.flashbackfix.compat;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Defines payloads whose authoritative value belongs to the current replay connection. */
public final class ReplayPayloadPolicy {

    private static final Set<ResourceLocation> CONNECTION_SCOPED = Set.of(
            id("rctmod", "player_state"),
            id("rctmod", "trainer_manager"),
            id("rctmod", "file_download"));

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

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
