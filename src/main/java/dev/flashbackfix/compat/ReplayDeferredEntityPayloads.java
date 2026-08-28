package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defers entity-addressed mod payloads until the replay entity and its complex spawn data exist.
 *
 * <p>Many mod packets intentionally discard themselves when their target entity is absent or not
 * initialized. A replay snapshot can deliver these packets in the same tick as vanilla add-entity
 * and NeoForge advanced-spawn packets, so dispatching them immediately is order-sensitive. Holding
 * conventional entity-id payloads until client tick tail fixes that protocol race generically.</p>
 */
public final class ReplayDeferredEntityPayloads {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final int MAX_WAIT_TICKS = 200;
    private static final List<PendingPayload> PENDING = new ArrayList<>();
    private static final Set<Integer> COMPLEX_SPAWN_READY = ConcurrentHashMap.newKeySet();

    private static ClientLevel activeLevel;
    private static boolean redispatching;

    private ReplayDeferredEntityPayloads() {
    }

    public static synchronized void reset() {
        PENDING.clear();
        COMPLEX_SPAWN_READY.clear();
        activeLevel = null;
        redispatching = false;
    }

    /** Returns true when the original listener invocation must be cancelled. */
    public static synchronized boolean defer(
            ClientPacketListener listener, ClientboundCustomPayloadPacket packet) {
        if (redispatching) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (!(minecraft.getSingleplayerServer() instanceof ReplayServer)) {
            reset();
            return false;
        }

        // NeoForge's advanced spawn payload initializes IEntityWithComplexSpawn itself. Deferring
        // this infrastructure packet creates a one-tick entity with null mod state, which is already
        // long enough for Flywheel to build (and permanently poison) a Create contraption visual.
        if (packet.payload() instanceof AdvancedAddEntityPayload advanced) {
            COMPLEX_SPAWN_READY.add(advanced.entityId());
            return false;
        }

        ReplayCreateElevatorCompat.rememberFloorPayload(packet.payload());

        Integer entityId = ModdedPayloadSnapshotCache.findEntityId(packet.payload());
        if (entityId == null) {
            return false;
        }
        if (level != null && activeLevel != null && activeLevel != level) {
            PENDING.clear();
        }
        if (level != null) {
            activeLevel = level;
        }
        PENDING.add(new PendingPayload(listener, packet, entityId, MAX_WAIT_TICKS));
        return true;
    }

    public static void flush(ClientLevel level) {
        List<PendingPayload> ready = new ArrayList<>();
        synchronized (ReplayDeferredEntityPayloads.class) {
            Minecraft minecraft = Minecraft.getInstance();
            if (level == null || !(minecraft.getSingleplayerServer() instanceof ReplayServer)) {
                reset();
                return;
            }
            if (activeLevel == null) {
                activeLevel = level;
            } else if (activeLevel != level) {
                PENDING.clear();
                activeLevel = level;
                return;
            }

            Iterator<PendingPayload> iterator = PENDING.iterator();
            while (iterator.hasNext()) {
                PendingPayload pending = iterator.next();
                if (level.getEntity(pending.entityId()) != null
                        || COMPLEX_SPAWN_READY.contains(pending.entityId())) {
                    ready.add(pending);
                    iterator.remove();
                } else if (pending.decrementAndExpired()) {
                    LOGGER.warn("Discarding replay payload {} after target entity {} did not spawn",
                            pending.packet().payload().type().id(), pending.entityId());
                    iterator.remove();
                }
            }
            redispatching = true;
        }

        try {
            for (PendingPayload pending : ready) {
                pending.packet().handle(pending.listener());
            }
        } finally {
            synchronized (ReplayDeferredEntityPayloads.class) {
                redispatching = false;
            }
        }
    }

    public static synchronized void onAddEntity(int entityId) {
        COMPLEX_SPAWN_READY.remove(entityId);
    }

    public static synchronized void remove(Iterable<Integer> entityIds) {
        for (int entityId : entityIds) {
            COMPLEX_SPAWN_READY.remove(entityId);
        }
    }

    private static final class PendingPayload {
        private final ClientPacketListener listener;
        private final ClientboundCustomPayloadPacket packet;
        private final int entityId;
        private int remainingTicks;

        private PendingPayload(ClientPacketListener listener, ClientboundCustomPayloadPacket packet,
                int entityId, int remainingTicks) {
            this.listener = listener;
            this.packet = packet;
            this.entityId = entityId;
            this.remainingTicks = remainingTicks;
        }

        private ClientPacketListener listener() {
            return this.listener;
        }

        private ClientboundCustomPayloadPacket packet() {
            return this.packet;
        }

        private int entityId() {
            return this.entityId;
        }

        private boolean decrementAndExpired() {
            return --this.remainingTicks <= 0;
        }
    }
}
