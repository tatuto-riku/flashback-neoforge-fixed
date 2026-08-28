package dev.flashbackfix.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps wrappers added by world-generation optimization mods outside Flashback's null sentinel.
 *
 * <p>Flashback deliberately replaces ServerChunkCache#getGeneratorState with {@code null} while a
 * ReplayServer constructs its synthetic levels, then wraps ensureStructuresGenerated to skip the
 * call. MixinExtras composes wrappers by priority; a third-party wrapper at the same invocation can
 * otherwise dereference that sentinel before Flashback's guard runs. This wrapper must have a higher
 * priority than those wrappers: MixinExtras then places this null check at the outer boundary of the
 * composed operation, before any third-party handler can observe the sentinel.</p>
 */
@Mixin(value = ServerLevel.class, priority = 2000)
public abstract class MixinServerLevelNullGeneratorState {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;ensureStructuresGenerated()V"))
    private void flashbackNeoForgeFixed$guardNullGeneratorState(
            ChunkGeneratorStructureState state, Operation<Void> original) {
        if (state != null) {
            original.call(state);
        }
    }
}
