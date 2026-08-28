package dev.flashbackfix.mixin;

import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.compat.ReplayComplexSpawnPairing;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes early NeoForge complex-spawn serialization recoverable on a replay server.
 *
 * <p>Flashback adds a pending entity to its fake ServerLevel as soon as a following game packet forces
 * a flush. NeoForge immediately starts pairing it to the replay viewer and calls writeSpawnData, but
 * the recorded AdvancedAddEntityPayload action is later in the snapshot. Mod fields which exist only
 * in that payload therefore have not been read yet. Create's carriage trainId and controlled
 * contraption controllerPos are concrete examples, but the ordering bug applies to every
 * IEntityWithComplexSpawn.</p>
 *
 * <p>The normal call is retained because Create/Flywheel requires the add-entity and complex-spawn
 * payloads in one pairing bundle. Only a serialization failure is swallowed and remembered. Once
 * ActionModdedPayload applies the recorded bytes, ReplayComplexSpawnPairing sends a complete atomic
 * replacement bundle.</p>
 */
@Mixin(ServerEntity.class)
public abstract class MixinServerEntityNeoForgePairing {

    @Redirect(
            method = "sendPairingData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;sendPairingData(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V"))
    private void flashbackNeoForgeFixed$useRecordedComplexSpawnData(
            Entity entity, ServerPlayer player, Consumer<CustomPacketPayload> payloads) {
        if (entity instanceof IEntityWithComplexSpawn
                && entity.level().getServer() instanceof ReplayServer) {
            if (ReplayComplexSpawnPairing.isInvalid(entity)) {
                return;
            }
            // A complex-spawn implementation may emit more than one payload before throwing. Do
            // not let a partial transaction escape into NeoForge's pairing bundle.
            List<CustomPacketPayload> buffered = new ArrayList<>();
            try {
                entity.sendPairingData(player, buffered::add);
            } catch (RuntimeException exception) {
                ReplayComplexSpawnPairing.rememberFailure(entity, exception);
                return;
            }
            buffered.forEach(payloads);
            return;
        }
        entity.sendPairingData(player, payloads);
    }
}
