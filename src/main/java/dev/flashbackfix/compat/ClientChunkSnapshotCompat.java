package dev.flashbackfix.compat;

import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Makes a server-format chunk packet safely from an already-existing client-side Sable plot. */
public final class ClientChunkSnapshotCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final ThreadLocal<Integer> CAPTURE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ClientChunkSnapshotCompat() {
    }

    public static <T> T capture(Supplier<T> supplier) {
        CAPTURE_DEPTH.set(CAPTURE_DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            int depth = CAPTURE_DEPTH.get() - 1;
            if (depth == 0) {
                CAPTURE_DEPTH.remove();
            } else {
                CAPTURE_DEPTH.set(depth);
            }
        }
    }

    /**
     * Some mod block entities use distinct client/server backing objects but implement getUpdateTag
     * as if it can only run on the server. Their tag is optional chunk initialization data; retaining
     * the block entity with an empty tag is safer than losing the complete physicalized structure.
     */
    public static CompoundTag getUpdateTag(
            BlockEntity blockEntity, HolderLookup.Provider registries) {
        if (CAPTURE_DEPTH.get() == 0) {
            return blockEntity.getUpdateTag(registries);
        }
        try {
            return blockEntity.getUpdateTag(registries);
        } catch (RuntimeException exception) {
            LOGGER.warn("Using an empty client snapshot tag for block entity {} at {} because its "
                            + "server-only serializer failed: {}",
                    blockEntity.getClass().getName(), blockEntity.getBlockPos(), exception.toString());
            return new CompoundTag();
        }
    }
}
