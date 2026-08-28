package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Restores Create state whose normal live-network lifetime does not account for replay seeks. */
public final class ReplayCreateElevatorCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final String FLOOR_PACKET_ID = "create:update_elevator_floors";
    private static final String ELEVATOR_CONTRAPTION =
            "com.simibubi.create.content.contraptions.elevator.ElevatorContraption";
    private static final Map<Integer, RecordedFloors> RECORDED_FLOORS = new ConcurrentHashMap<>();

    private static ClientLevel activeLevel;
    private static int lastReplayTick = -1;
    private static long clientTick;
    private static long snapActuatorsThroughTick = Long.MIN_VALUE;
    private static boolean recordedEntityAuthorityAfterSeek;
    private static long recordedElevatorSeekEpoch;

    private ReplayCreateElevatorCompat() {
    }

    public static synchronized void reset() {
        RECORDED_FLOORS.clear();
        activeLevel = null;
        lastReplayTick = -1;
        clientTick = 0;
        snapActuatorsThroughTick = Long.MIN_VALUE;
        recordedEntityAuthorityAfterSeek = false;
        recordedElevatorSeekEpoch = 0;
    }

    /** Retains the recorded floor list until the advanced-spawn contraption is ready to accept it. */
    public static void rememberFloorPayload(CustomPacketPayload payload) {
        if (!FLOOR_PACKET_ID.equals(payload.type().id().toString())) {
            return;
        }
        try {
            Method entityIdAccessor = payload.getClass().getMethod("entityId");
            Method floorsAccessor = payload.getClass().getMethod("floors");
            int entityId = (Integer) entityIdAccessor.invoke(payload);
            Object value = floorsAccessor.invoke(payload);
            if (value instanceof List<?> floors) {
                RECORDED_FLOORS.put(entityId, new RecordedFloors(new ArrayList<>(floors)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not retain recorded Create elevator floor payload {}", payload.getClass(), e);
        }
    }

    public static void tick(ClientLevel level) {
        if (!isActive(level)) {
            return;
        }
        clientTick++;
        observeReplayTick();

        for (Map.Entry<Integer, RecordedFloors> entry : RECORDED_FLOORS.entrySet()) {
            applyFloors(level, entry.getKey(), entry.getValue());
        }
    }

    /** True only across a discontinuous backwards seek, never during ordinary replay playback. */
    public static synchronized boolean shouldSnapActuatorState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getSingleplayerServer() instanceof ReplayServer) || minecraft.level == null) {
            return false;
        }
        observeReplayTick();
        return clientTick <= snapActuatorsThroughTick;
    }

    /**
     * Create can reconstruct ordinary forward playback from its controller, but it cannot reconstruct
     * the controller's hidden velocity phase after a discontinuous seek. From the first rewind onward,
     * use Flashback's per-recorded-tick entity positions as the authoritative contraption transform.
     */
    public static synchronized boolean useRecordedElevatorPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.getSingleplayerServer() instanceof ReplayServer) || minecraft.level == null) {
            return false;
        }
        observeReplayTick();
        return recordedEntityAuthorityAfterSeek;
    }

    public static synchronized long recordedElevatorSeekEpoch() {
        observeReplayTick();
        return recordedElevatorSeekEpoch;
    }

    private static synchronized void observeReplayTick() {
        if (!(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer replayServer)) {
            return;
        }
        int replayTick = replayServer.getReplayTick();
        if (lastReplayTick >= 0 && replayTick < lastReplayTick) {
            // Snapshot and catch-up packets can all reach the render thread in one burst. Keep the
            // discontinuity window open for a few client ticks so every actuator packet in that
            // burst snaps to recorded state instead of retaining an obsolete interpolation delta.
            // Local replay packets normally arrive as one render-thread burst. One additional
            // client tick covers a split burst without suppressing the first genuine post-seek
            // update, which must be allowed to resume recorded movement.
            snapActuatorsThroughTick = clientTick + 1;
            recordedEntityAuthorityAfterSeek = true;
            recordedElevatorSeekEpoch++;
        }
        lastReplayTick = replayTick;
    }

    private static void applyFloors(ClientLevel level, int entityId, RecordedFloors recorded) {
        Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        try {
            Method getContraption = entity.getClass().getMethod("getContraption");
            Object contraption = getContraption.invoke(entity);
            if (contraption == null || !ELEVATOR_CONTRAPTION.equals(contraption.getClass().getName())) {
                return;
            }

            Field namesList = contraption.getClass().getField("namesList");
            Object current = namesList.get(contraption);
            if (!recorded.floors.equals(current)) {
                namesList.set(contraption, recorded.floors);
                contraption.getClass().getMethod("syncControlDisplays").invoke(contraption);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!recorded.loggedFailure) {
                recorded.loggedFailure = true;
                LOGGER.warn("Could not apply recorded floors to Create elevator entity {} ({})",
                        entityId, entity.getClass(), e);
            }
        }
    }

    private static boolean isActive(ClientLevel level) {
        if (level == null || !(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            reset();
            return false;
        }
        if (activeLevel == null) {
            activeLevel = level;
        } else if (activeLevel != level) {
            reset();
            activeLevel = level;
        }
        return true;
    }

    private static final class RecordedFloors {
        private final List<?> floors;
        private volatile boolean loggedFailure;

        private RecordedFloors(List<?> floors) {
            this.floors = floors;
        }
    }
}
