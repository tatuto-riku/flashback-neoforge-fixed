package dev.flashbackfix.mixin;

import dev.flashbackfix.FlashbackNeoForgeFixed;
import dev.flashbackfix.compat.BlockEntityPacketSnapshotCache;
import dev.flashbackfix.compat.ReplayAuthoritativeEntityCompat;
import dev.flashbackfix.compat.ReplayCreateElevatorCompat;
import dev.flashbackfix.compat.ReplayDeferredEntityPayloads;
import dev.flashbackfix.compat.ModdedPayloadSnapshotCache;
import dev.flashbackfix.compat.SableReplayRidingCompat;
import dev.flashbackfix.compat.SmoothMovementReplayCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records vanilla riding relationships even when their direct plot vehicle has not spawned yet. */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerSableRiding {

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$cacheExactBlockEntityState(
            ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        BlockEntityPacketSnapshotCache.capture(Minecraft.getInstance().level, packet);
    }

    @Inject(method = "handleMoveEntity", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$preservePlotMoveSpace(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.beforeMovement(level, packet.getEntity(level), packet);
        }
    }

    @Inject(method = "handleMoveEntity", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$rememberPlotMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            ReplayAuthoritativeEntityCompat.afterMove(level, packet);
        }
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.afterMovement(level, packet.getEntity(level));
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$preservePlotTeleportSpace(
            ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        // Must run before LivingEntity#lerpTo: Smooth Movement's TAIL handler reads this flag while
        // the teleport packet is being applied.
        SmoothMovementReplayCompat.updateClientState();
        ClientLevel level = Minecraft.getInstance().level;
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.beforeMovement(level, level.getEntity(packet.getId()), packet);
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$rememberPlotTeleport(
            ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            ReplayAuthoritativeEntityCompat.afterTeleport(level, packet);
            SmoothMovementReplayCompat.clampTeleportInterpolation(
                    level.getEntity(packet.getId()));
        }
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.afterMovement(level, level.getEntity(packet.getId()));
        }
    }

    @Inject(method = "handleAddEntity", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$resetReusedRidingId(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        ReplayDeferredEntityPayloads.onAddEntity(packet.getId());
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.onAddEntity(level, packet.getId());
        }
    }

    // RETURN targets every return instruction, including vanilla's early "unknown vehicle" path.
    // That turns a packet-order failure into a pending relationship instead of losing it forever.
    @Inject(method = "handleSetEntityPassengersPacket", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$rememberRiding(ClientboundSetPassengersPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.onPassengers(level, packet);
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At("RETURN"))
    private void flashbackNeoForgeFixed$forgetRemovedRidingEntities(
            ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        ReplayAuthoritativeEntityCompat.remove(packet.getEntityIds());
        ReplayDeferredEntityPayloads.remove(packet.getEntityIds());
        if (FlashbackNeoForgeFixed.isSableLoaded && level != null) {
            SableReplayRidingCompat.onRemoveEntities(level, packet.getEntityIds());
        }
    }

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$resetRidingOnLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        ModdedPayloadSnapshotCache.reset();
        BlockEntityPacketSnapshotCache.reset();
        ReplayAuthoritativeEntityCompat.reset();
        ReplayCreateElevatorCompat.reset();
        ReplayDeferredEntityPayloads.reset();
        if (FlashbackNeoForgeFixed.isSableLoaded) {
            SableReplayRidingCompat.reset();
        }
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void flashbackNeoForgeFixed$resetRidingOnRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ReplayAuthoritativeEntityCompat.reset();
        ReplayCreateElevatorCompat.reset();
        ReplayDeferredEntityPayloads.reset();
        if (FlashbackNeoForgeFixed.isSableLoaded) {
            SableReplayRidingCompat.reset();
        }
    }
}
