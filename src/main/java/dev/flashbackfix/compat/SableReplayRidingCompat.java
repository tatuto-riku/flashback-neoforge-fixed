package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.packet_mixin.PacketActuallyInSubLevelExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps cross-boundary riding relationships stable during replay.
 *
 * <p>A Sable-retained vehicle (Create seats, minecarts, armor stands, and similar entities) lives in
 * the remote plot grid on the viewing client, while a player or other kickable passenger is owned by
 * Flashback's reconstructed server at its transformed world position. A vanilla passengers packet is
 * therefore the only object joining the two worlds. It may arrive before either side is spawned, and
 * Flashback's later position updates can run after vanilla's riding tick. Remembering the relationship
 * and reapplying the vehicle's real {@link Entity#positionRider(Entity)} operation at the end of each
 * client tick lets Sable perform its own plot-to-world position and orientation transforms exactly as
 * it does in live play.
 */
public final class SableReplayRidingCompat {

    private static final Map<Integer, int[]> PASSENGERS_BY_VEHICLE = new ConcurrentHashMap<>();
    /**
     * Direct entities are identified while their add packet still places them in the plot grid.
     * Their runtime position is not a stable ownership test: Sable's movement handler can project a
     * local packet into world space, which is exactly what this compatibility layer has to repair.
     */
    private static final Map<Integer, PlotEntityState> DIRECT_PLOT_ENTITIES = new ConcurrentHashMap<>();
    private static volatile ClientLevel activeLevel;

    private SableReplayRidingCompat() {
    }

    public static void reset() {
        PASSENGERS_BY_VEHICLE.clear();
        DIRECT_PLOT_ENTITIES.clear();
        activeLevel = null;
    }

    public static void onAddEntity(ClientLevel level, int entityId) {
        if (!isActive(level)) {
            return;
        }

        // A direct packet may legitimately arrive in passengers -> add-entity order. Vanilla drops
        // that passengers packet as an unknown vehicle, while onPassengers() intentionally keeps it
        // pending. Never clear it here: doing so loses the only cross-boundary link at exactly the
        // moment the vehicle becomes resolvable. Explicit remove packets and level replacement are
        // the authoritative lifetime boundaries and already clear stale ids.
        Entity addedEntity = level.getEntity(entityId);
        SubLevel subLevel = addedEntity == null ? null : Sable.HELPER.getContaining(addedEntity);
        if (subLevel != null) {
            DIRECT_PLOT_ENTITIES.put(entityId,
                    new PlotEntityState(subLevel.getUniqueId(), addedEntity.position()));
        } else if (DIRECT_PLOT_ENTITIES.remove(entityId) != null) {
            // A genuine vanilla id reuse after removal must not inherit the old plot identity.
            PASSENGERS_BY_VEHICLE.remove(entityId);
        }
    }

    public static void onRemoveEntities(ClientLevel level, Iterable<Integer> removedIds) {
        if (!isActive(level)) {
            return;
        }
        for (int removedId : removedIds) {
            PASSENGERS_BY_VEHICLE.remove(removedId);
            DIRECT_PLOT_ENTITIES.remove(removedId);

            // A replay seek reconstructs ordinary entities by removing the old client copy and
            // adding a new copy with the same recorded id. The direct Sable seat survives through a
            // different packet stream, so deleting the passenger id here turns that transient
            // reconstruction step into a permanent dismount. ClientboundSetPassengersPacket is the
            // protocol authority for riding state: its empty form clears the relationship in
            // onPassengers(), while a temporary passenger absence remains pending until the entity
            // is recreated. Level replacement/reset and vehicle removal still clear stale state.
        }
    }

    public static void onPassengers(ClientLevel level, ClientboundSetPassengersPacket packet) {
        if (!isActive(level)) {
            return;
        }

        // An empty list is the authoritative dismount state. It must clear even when the vehicle was
        // removed just before the packet arrived; retaining it as a pending relationship would leave
        // a stale id ready to affect a later entity-id reuse.
        if (packet.getPassengers().length == 0) {
            PASSENGERS_BY_VEHICLE.remove(packet.getVehicle());
            return;
        }

        Entity vehicle = level.getEntity(packet.getVehicle());
        // An unresolved vehicle must be retained: direct snapshot packets can precede the normal
        // replay-server entity stream. Once it resolves, reconcile() discards ordinary-world
        // relationships and keeps only Sable plot vehicles.
        if (vehicle == null || isDirectPlotEntity(vehicle)) {
            PASSENGERS_BY_VEHICLE.put(packet.getVehicle(), packet.getPassengers().clone());
        }
    }

    public static void reconcile(ClientLevel level) {
        if (!isActive(level)) {
            return;
        }

        for (Map.Entry<Integer, int[]> entry : PASSENGERS_BY_VEHICLE.entrySet()) {
            Entity vehicle = level.getEntity(entry.getKey());
            if (vehicle == null) {
                continue;
            }
            PlotEntityState plotState = DIRECT_PLOT_ENTITIES.get(entry.getKey());
            if (plotState == null) {
                PASSENGERS_BY_VEHICLE.remove(entry.getKey(), entry.getValue());
                continue;
            }

            SubLevel vehicleSubLevel = resolveSubLevel(level, plotState);
            if (vehicleSubLevel == null) {
                continue;
            }

            // Sable decides whether a vehicle belongs to a sub-level from its current chunk. A
            // replayed movement packet with a missing/false local-space marker projects a retained
            // vehicle into world coordinates, after which both positionRider and render orientation
            // lose the sub-level. Restore its last authoritative plot coordinate before reconciling.
            if (Sable.HELPER.getContaining(vehicle) != vehicleSubLevel) {
                Vec3 localPosition = plotState.localPosition;
                vehicle.setPos(localPosition);
                vehicle.syncPacketPositionCodec(localPosition.x, localPosition.y, localPosition.z);
                EntitySubLevelUtil.setOldPosNoMovement(vehicle);
            } else {
                plotState.localPosition = vehicle.position();
            }

            int[] desiredPassengerIds = entry.getValue();
            for (Entity currentPassenger : vehicle.getPassengers()) {
                if (Arrays.stream(desiredPassengerIds).noneMatch(id -> id == currentPassenger.getId())) {
                    currentPassenger.stopRiding();
                }
            }

            for (int passengerId : desiredPassengerIds) {
                Entity passenger = level.getEntity(passengerId);
                if (passenger == null) {
                    continue;
                }
                if (passenger.getVehicle() != vehicle) {
                    passenger.startRiding(vehicle, true);
                }

                // This calls the vehicle-specific seat offset first, then Sable's existing TAIL
                // injection transforms the passenger from plot coordinates into the sub-level's
                // current world pose. Its renderer subsequently inherits the same pose quaternion.
                vehicle.positionRider(passenger);
            }
        }
    }

    /**
     * Restores Sable's local-space bit before its own ClientPacketListener wrapper interprets the
     * coordinates. Direct plot entities never exist in Flashback's fake server, so any movement for
     * one of these ids belongs to the plot-local stream captured from the original connection.
     */
    public static void beforeMovement(ClientLevel level, Entity entity, Object packet) {
        if (!isActive(level) || entity == null || !DIRECT_PLOT_ENTITIES.containsKey(entity.getId())) {
            return;
        }
        if (packet instanceof PacketActuallyInSubLevelExtension extension
                && !extension.sable$isActuallyInSubLevel()) {
            extension.sable$setActuallyInSubLevel(true);
        }
    }

    /** Updates the recovery anchor after Sable has successfully applied a local movement packet. */
    public static void afterMovement(ClientLevel level, Entity entity) {
        if (!isActive(level) || entity == null) {
            return;
        }
        PlotEntityState state = DIRECT_PLOT_ENTITIES.get(entity.getId());
        if (state == null) {
            return;
        }
        SubLevel containing = Sable.HELPER.getContaining(entity);
        if (containing != null && state.subLevelId.equals(containing.getUniqueId())) {
            state.localPosition = entity.position();
        }
    }

    private static boolean isDirectPlotEntity(Entity entity) {
        return entity != null && DIRECT_PLOT_ENTITIES.containsKey(entity.getId());
    }

    private static SubLevel resolveSubLevel(net.minecraft.world.level.Level level, PlotEntityState state) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return container == null ? null : container.getSubLevel(state.subLevelId);
    }

    private static final class PlotEntityState {
        private final UUID subLevelId;
        private volatile Vec3 localPosition;

        private PlotEntityState(UUID subLevelId, Vec3 localPosition) {
            this.subLevelId = subLevelId;
            this.localPosition = localPosition;
        }
    }

    private static boolean isActive(ClientLevel level) {
        if (level == null || !(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            reset();
            return false;
        }
        if (activeLevel != level) {
            PASSENGERS_BY_VEHICLE.clear();
            activeLevel = level;
        }
        return true;
    }
}
