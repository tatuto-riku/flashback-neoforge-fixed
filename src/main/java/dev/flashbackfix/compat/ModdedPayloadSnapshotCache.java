package dev.flashbackfix.compat;

import com.moulberry.flashback.io.ReplayWriter;
import com.moulberry.flashback.playback.ReplayServer;
import dev.flashbackfix.FlashbackNeoForgeFixed;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.flashbackfix.action.ActionModdedSnapshotPayload;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A generic last-known-state cache for modded clientbound payloads.
 *
 * <p>Vanilla state is synthesized by Flashback when a recording snapshot starts, but many mods keep
 * client state exclusively in a custom payload and deliberately omit it from entity/block-entity
 * NBT. Such state predates the recorder and cannot be recovered by recording packets only after the
 * record button was pressed. Keep the latest payload per type and logical entity target from the
 * live connection, then append those values to every snapshot. This is intentionally protocol based;
 * it has no dependency on Create or any other content mod.</p>
 */
public final class ModdedPayloadSnapshotCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final int MAX_ENTRIES = 2048;
    private static final String CREATE_RAIL_GRAPH_SYNC = "create:sync_rail_graph";
    private static final Map<PayloadKey, CachedPayload> LATEST = new LinkedHashMap<>();
    /**
     * Some protocols build one logical state from an ordered packet series rather than replacing it
     * with one last-known value. Create's full rail graph is the important example: the first packet
     * clears the graph and following packets append more nodes/edges. Collapsing those packets by
     * payload type leaves a graph whose checksum is valid only for its final fragment.
     */
    private static final Map<UUID, List<CachedPayload>> CREATE_RAIL_GRAPHS = new HashMap<>();
    /** Completed generic multi-packet transactions, keyed like ordinary last-known state. */
    private static final Map<PayloadKey, List<CachedPayload>> COMPLETED_BATCHES = new LinkedHashMap<>();
    /** A transaction currently arriving; never emitted into a snapshot until its final fragment. */
    private static final Map<PayloadKey, List<CachedPayload>> BUILDING_BATCHES = new HashMap<>();
    private static final Map<PayloadKey, Long> LAST_BATCH_REMAINING = new HashMap<>();
    private static long sequence;

    private ModdedPayloadSnapshotCache() {
    }

    public static synchronized void reset() {
        LATEST.clear();
        CREATE_RAIL_GRAPHS.clear();
        COMPLETED_BATCHES.clear();
        BUILDING_BATCHES.clear();
        LAST_BATCH_REMAINING.clear();
        sequence = 0;
    }

    public static synchronized void capture(
            CustomPacketPayload payload, ActionModdedPayload.EncodedPayload encoded) {
        if (payload == null || Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer) {
            return;
        }
        ResourceLocation id = payload.type().id();
        if (shouldExclude(id)
                || encoded == null
                || encoded.protocol() != ConnectionProtocol.PLAY
                || !id.equals(encoded.id())) {
            return;
        }

        if (CREATE_RAIL_GRAPH_SYNC.equals(id.toString()) && captureCreateRailGraph(payload, encoded)) {
            return;
        }

        PayloadKey key = new PayloadKey(id, findLogicalTarget(payload));
        Long remainingBatches = findRemainingBatches(payload);
        if (remainingBatches != null) {
            captureBatch(key, encoded, remainingBatches);
            return;
        }
        LATEST.remove(key);
        LATEST.put(key, new CachedPayload(++sequence, encoded));
        while (LATEST.size() > MAX_ENTRIES) {
            PayloadKey oldest = LATEST.keySet().iterator().next();
            LATEST.remove(oldest);
        }
    }

    public static synchronized void appendSnapshotTasks(List<Consumer<ReplayWriter>> tasks) {
        if (LATEST.isEmpty() && CREATE_RAIL_GRAPHS.isEmpty() && COMPLETED_BATCHES.isEmpty()) {
            return;
        }
        List<CachedPayload> snapshot = new ArrayList<>(LATEST.values());
        CREATE_RAIL_GRAPHS.values().forEach(snapshot::addAll);
        COMPLETED_BATCHES.values().forEach(snapshot::addAll);
        snapshot.sort(Comparator.comparingLong(CachedPayload::sequence));
        for (CachedPayload cached : snapshot) {
            ActionModdedPayload.EncodedPayload payload = cached.payload();
            tasks.add(writer -> ActionModdedSnapshotPayload.write(writer, payload));
        }
    }

    /**
     * Retains the complete current Create graph transaction. A full-wipe packet starts a new base;
     * subsequent packets are ordered deltas on that base. This cannot use the ordinary last-value
     * cache because Create deliberately splits large graphs into chunks of at most 1000 entries.
     */
    private static boolean captureCreateRailGraph(CustomPacketPayload payload,
            ActionModdedPayload.EncodedPayload encoded) {
        Object graphIdValue = readField(payload, "graphId");
        Object fullWipeValue = readField(payload, "fullWipe");
        Object deletesGraphValue = readField(payload, "packetDeletesGraph");
        if (!(graphIdValue instanceof UUID graphId)
                || !(fullWipeValue instanceof Boolean fullWipe)
                || !(deletesGraphValue instanceof Boolean deletesGraph)) {
            return false;
        }

        List<CachedPayload> packets = CREATE_RAIL_GRAPHS.computeIfAbsent(graphId,
                ignored -> new ArrayList<>());
        if (fullWipe || deletesGraph) {
            packets.clear();
        }
        packets.add(new CachedPayload(++sequence, encoded));

        // A delete is itself the complete current state. Keeping an empty list would allow an older
        // graph to survive when a later snapshot is opened.
        if (packets.size() > MAX_ENTRIES) {
            LOGGER.warn("Create rail graph {} exceeded the {} packet snapshot limit; retaining the"
                    + " complete sequence to avoid corrupting its node id mapping", graphId, MAX_ENTRIES);
        }
        return true;
    }

    /**
     * Preserves an explicit countdown-based packet batch as one ordered state transaction. Keeping
     * only its last fragment produces a syntactically valid custom payload whose inner serialized
     * object is corrupt (RCTMod PlayerState is one example).
     */
    private static void captureBatch(PayloadKey key, ActionModdedPayload.EncodedPayload encoded,
            long remaining) {
        Long previous = LAST_BATCH_REMAINING.get(key);
        List<CachedPayload> building = BUILDING_BATCHES.computeIfAbsent(key,
                ignored -> new ArrayList<>());
        if (previous == null || remaining >= previous) {
            building.clear();
        }
        building.add(new CachedPayload(++sequence, encoded));
        LAST_BATCH_REMAINING.put(key, remaining);
        if (remaining == 0) {
            COMPLETED_BATCHES.put(key, List.copyOf(building));
            building.clear();
            LAST_BATCH_REMAINING.remove(key);
        }
    }

    public static Long findRemainingBatches(CustomPacketPayload payload) {
        Class<?> type = payload.getClass();
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (method.getParameterCount() != 0
                    || !name.contains("remaining") || !name.contains("batch")) {
                continue;
            }
            try {
                // Public accessors must be attempted before setAccessible. A number of mods live in
                // named Java modules which export public networking APIs but do not open their
                // packages for deep reflection; forcing accessibility first therefore fails even
                // though an ordinary public invocation is legal.
                Object value;
                try {
                    value = method.invoke(payload);
                } catch (IllegalAccessException inaccessible) {
                    method.setAccessible(true);
                    value = method.invoke(payload);
                }
                if (value instanceof Number number && number.longValue() >= 0) {
                    return number.longValue();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        Object fieldValue = readField(payload, "remainingBatches");
        return fieldValue instanceof Number number && number.longValue() >= 0
                ? number.longValue() : null;
    }

    private static boolean shouldExclude(ResourceLocation id) {
        String namespace = id.getNamespace();
        // Sable has a complete synthesized sub-level snapshot in SableCompat and its continuous
        // pose stream would otherwise dominate the cache. Framework/Flashback payloads establish
        // connection mechanics rather than persistent world state.
        return namespace.equals("minecraft")
                || namespace.equals("neoforge")
                || namespace.equals("sable")
                || namespace.equals("flashback")
                || namespace.equals(FlashbackNeoForgeFixed.MODID)
                || ReplayPayloadPolicy.isConnectionScoped(id);
    }

    private static Object findLogicalTarget(CustomPacketPayload payload) {
        Integer entityId = findEntityId(payload);
        if (entityId != null) {
            return entityId;
        }
        UUID directId = findUuidId(payload);
        if (directId != null) {
            return directId;
        }
        // Create's AddTrainPacket wraps the UUID-bearing Train in its `train` record component.
        // Key it by that UUID so recordings started with several existing trains retain all of them.
        if ("create:add_train".equals(payload.type().id().toString()) && payload.getClass().isRecord()) {
            for (RecordComponent component : payload.getClass().getRecordComponents()) {
                if (!component.getName().equalsIgnoreCase("train")) {
                    continue;
                }
                try {
                    UUID trainId = findUuidId(component.getAccessor().invoke(payload));
                    if (trainId != null) {
                        return trainId;
                    }
                } catch (ReflectiveOperationException ignored) {
                    break;
                }
            }
        }
        // A singleton key means the most recent packet of this type is the best generic snapshot
        // available. Entity-addressed records keep one value per entity via findEntityId.
        return payload.getClass().getName();
    }

    private static UUID findUuidId(Object value) {
        if (value == null) {
            return null;
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (!component.getName().equalsIgnoreCase("id")) {
                    continue;
                }
                try {
                    Object id = component.getAccessor().invoke(value);
                    if (id instanceof UUID uuid) {
                        return uuid;
                    }
                } catch (ReflectiveOperationException ignored) {
                    break;
                }
            }
        }
        Object id = readField(value, "id");
        return id instanceof UUID uuid ? uuid : null;
    }

    private static Object readField(Object instance, String name) {
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Finds the conventional target entity id without depending on a mod's packet class. This is
     * shared with replay delivery so entity state can wait until complex spawn data has been applied.
     */
    public static Integer findEntityId(CustomPacketPayload payload) {
        Class<?> type = payload.getClass();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.contains("entity") || !name.contains("id")) {
                    continue;
                }
                try {
                    Object value = component.getAccessor().invoke(payload);
                    if (value instanceof Number number) {
                        return number.intValue();
                    }
                } catch (ReflectiveOperationException ignored) {
                    break;
                }
            }
        }
        for (String methodName : List.of("entityId", "getEntityId")) {
            try {
                Method method = type.getMethod(methodName);
                if (method.getParameterCount() == 0) {
                    Object value = method.invoke(payload);
                    if (value instanceof Number number) {
                        return number.intValue();
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private record PayloadKey(ResourceLocation type, Object target) {
    }

    private record CachedPayload(long sequence, ActionModdedPayload.EncodedPayload payload) {
    }
}
