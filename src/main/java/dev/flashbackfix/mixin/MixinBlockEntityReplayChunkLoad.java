package dev.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.ReplayChunkBlockEntityLoadScope;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Isolates an incompatible client update tag while a replay chunk is applied to Flashback's server
 * mirror. The original tag is delivered separately to the real client after the chunk is tracked.
 */
@Mixin(BlockEntity.class)
public class MixinBlockEntityReplayChunkLoad {

    @Unique
    private static final Logger FLASHBACK_NEOFORGE_FIXED$LOGGER =
            LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    @Unique
    private static final Set<String> FLASHBACK_NEOFORGE_FIXED$LOGGED_LOAD_FAILURES =
            ConcurrentHashMap.newKeySet();
    @Unique
    private static final String FLASHBACK_NEOFORGE_FIXED$COBBLEMON_HABITAT =
            "com.cobblemon.mod.common.block.habitat.HabitatBlockEntity";

    @WrapMethod(method = "loadWithComponents")
    private void flashbackNeoForgeFixed$isolateReplayClientTagFailure(
            CompoundTag tag, HolderLookup.Provider registries, Operation<Void> original) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        boolean replayChunkLoad = ReplayChunkBlockEntityLoadScope.isActive()
                && blockEntity.getLevel() != null
                && blockEntity.getLevel().getServer() instanceof ReplayServer;
        if (!replayChunkLoad) {
            original.call(tag, registries);
            return;
        }

        String type = blockEntity.getClass().getName();
        if (FLASHBACK_NEOFORGE_FIXED$COBBLEMON_HABITAT.equals(type)) {
            // Habitat's chunk update tag intentionally omits persistence-only values. Its server
            // decoder calls Enum.valueOf on the absent PhaseOrder and cannot consume that client
            // schema. The exact tag is still forwarded to the real replay client.
            return;
        }

        try {
            // Most block entities, including Copycats, need this call to rebuild their material or
            // other server-mirror state. Only the individual incompatible client tag is isolated.
            original.call(tag, registries);
        } catch (RuntimeException exception) {
            if (FLASHBACK_NEOFORGE_FIXED$LOGGED_LOAD_FAILURES.add(type)) {
                FLASHBACK_NEOFORGE_FIXED$LOGGER.warn(
                        "Kept incompatible replay chunk update tag opaque for {} at {}",
                        type, blockEntity.getBlockPos(), exception);
            }
        }
    }
}
