package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Repairs an entity whose first NeoForge pairing ran before its recorded complex spawn data. */
public final class ReplayComplexSpawnPairing {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final Set<Entity> FAILED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Entity> INVALID = Collections.newSetFromMap(new IdentityHashMap<>());

    private ReplayComplexSpawnPairing() {
    }

    public static synchronized void rememberFailure(Entity entity, RuntimeException exception) {
        if (FAILED.add(entity)) {
            LOGGER.warn("Deferring incomplete replay complex-spawn pairing for entity {} ({}) until recorded data is applied: {}",
                    entity.getId(), entity.getType(), exception.toString());
        }
    }

    /** Suppresses pairing after a recorded payload failed validation or partially mutated its target. */
    public static synchronized void blockInvalid(Entity entity) {
        INVALID.add(entity);
    }

    /** A later valid timeline payload may fully initialize the same entity and make it pairable. */
    public static synchronized void clearInvalid(Entity entity) {
        INVALID.remove(entity);
    }

    public static synchronized boolean isInvalid(Entity entity) {
        return INVALID.contains(entity);
    }

    public static void repairIfFailed(ReplayServer replayServer, Entity entity) {
        if (entity == null) {
            return;
        }
        synchronized (ReplayComplexSpawnPairing.class) {
            if (INVALID.contains(entity)) {
                return;
            }
            if (!FAILED.remove(entity)) {
                return;
            }
        }
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()) {
            return;
        }

        for (ServerPlayer viewer : replayServer.getReplayViewers()) {
            try {
                List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
                packets.add(new ClientboundRemoveEntitiesPacket(entity.getId()));
                ServerEntity pairing = new ServerEntity(level, entity, 1, true, ignored -> { });
                pairing.sendPairingData(viewer, new PacketAndPayloadAcceptor<>(packets::add));
                viewer.connection.send(new ClientboundBundlePacket(packets));
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to repair replay complex-spawn pairing for entity {} ({})",
                        entity.getId(), entity.getType(), exception);
            }
        }
    }
}
