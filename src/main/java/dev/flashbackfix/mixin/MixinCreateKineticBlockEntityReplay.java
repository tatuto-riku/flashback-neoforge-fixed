package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.ReplayStateCompat;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps replay-side Create simulation from replacing the recorded kinetic speed with zero. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class MixinCreateKineticBlockEntityReplay {

    /**
     * Create turns {@link #getSpeed()} into zero whenever the client tick-rate manager is frozen.
     * Flashback deliberately keeps that manager frozen while it owns the replay clock, including
     * while snapshot block entities and their Flywheel visuals are constructed. Consequently the
     * visual captures a rotational speed of zero and has no reason to rebuild merely because the
     * replay starts playing (or seeks).
     *
     * <p>The recorded {@code Speed} field remains the authority in a replay. Bypass only Create's
     * vanilla-freeze mask while its visual reads that field; Flashback's replay/render clock still
     * controls whether time itself advances, so this does not resume ordinary world simulation.</p>
     */
    @Redirect(
            method = "getSpeed",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/TickRateManager;isFrozen()Z"),
            require = 1)
    private boolean flashbackNeoForgeFixed$useRecordedSpeedForReplayVisuals(
            TickRateManager tickRateManager) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        if (blockEntity.getLevel() != null
                && blockEntity.getLevel().isClientSide
                && ReplayStateCompat.isInReplay()) {
            return false;
        }
        return tickRateManager.isFrozen();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashbackNeoForgeFixed$preserveRecordedKineticState(CallbackInfo ci) {
        BlockEntity blockEntity = (BlockEntity) (Object) this;
        if (blockEntity.getLevel() != null
                && !blockEntity.getLevel().isClientSide
                && blockEntity.getLevel().getServer() instanceof ReplayServer) {
            ci.cancel();
        }
    }
}
