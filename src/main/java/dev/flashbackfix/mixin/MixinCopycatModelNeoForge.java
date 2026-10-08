package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.CopycatsLegacyReplayCompat;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Makes Copycats' simple-material fallback valid for multi-state blocks in legacy replays. */
@Pseudo
@Mixin(targets = "com.copycatsplus.copycats.foundation.copycat.model.neoforge.CopycatModelNeoForge",
        remap = false)
public abstract class MixinCopycatModelNeoForge {

    @Redirect(
            method = "gatherModelData",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/copycatsplus/copycats/foundation/copycat/model/neoforge/CopycatModelNeoForge;getMaterials(Lnet/neoforged/neoforge/client/model/data/ModelData;)Ljava/util/Map;",
                    ordinal = 0),
            require = 1)
    private Map<?, ?> flashbackNeoForgeFixed$readReplaySafeMaterials(
            ModelData queriedData,
            ModelData.Builder builder,
            BlockAndTintGetter world,
            BlockPos pos,
            BlockState state,
            ModelData blockEntityData) {
        return CopycatsLegacyReplayCompat.getReplaySafeMaterials(queriedData, state);
    }
}
