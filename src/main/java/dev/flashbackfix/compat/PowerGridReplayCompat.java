package dev.flashbackfix.compat;

import com.moulberry.flashback.io.ReplayWriter;
import dev.flashbackfix.action.ActionModdedPayload;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconstructs PowerGrid wire state on Flashback's fake server.
 *
 * <p>PowerGrid deliberately leaves the wire item and endpoints out of the vanilla add-entity packet.
 * They travel in an {@code EntityDataS2CPacket} immediately before that packet instead. Flashback
 * reconstructs a server entity from the vanilla packet, so an uninitialized wire can reach entity
 * tracking and crash when PowerGrid asks it to serialize {@code wireEntry}. Keep the protocol optional
 * and reflective, apply the recorded state to the fake-server entity, and synthesize one state packet
 * per existing wire in every new snapshot.</p>
 */
public final class PowerGridReplayCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final String PACKETS_CLASS = "org.patryk3211.powergrid.collections.ModPackets";
    private static final String ENTITY_DATA_CLASS =
            "org.patryk3211.powergrid.network.packets.EntityDataS2CPacket";
    private static final String BASE_WIRE_CLASS =
            "org.patryk3211.powergrid.electricity.wire.BaseWireEntity";
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    private static volatile Protocol protocol;
    private static volatile boolean unavailable;

    private PowerGridReplayCompat() {
    }

    /** Returns entity data only when the shared PowerGrid channel contains that packet subtype. */
    public static EntityData decodeEntityData(ResourceLocation id, byte[] encodedBytes) {
        Protocol current = protocol();
        if (current == null || !current.channel().equals(id)) {
            return null;
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedBytes));
        try {
            int packetType = buffer.readVarInt();
            if (packetType != current.entityDataPacketId()) {
                return null;
            }
            int entityId = buffer.readInt();
            CompoundTag data = buffer.readNbt();
            if (data == null) {
                logOnce("decode-null", "Ignoring a PowerGrid entity-data replay payload with no NBT");
                return null;
            }
            return new EntityData(entityId, data.copy());
        } catch (RuntimeException exception) {
            logOnce("decode", "Failed to decode PowerGrid entity data from the replay", exception);
            return null;
        } finally {
            buffer.release();
        }
    }

    /** Adds a complete, per-wire image instead of collapsing the shared channel to one last packet. */
    public static void appendSnapshotTasks(
            ClientLevel level, List<Consumer<ReplayWriter>> tasks) {
        Protocol current = protocol();
        if (current == null) {
            return;
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || !current.baseWireClass().isInstance(entity)) {
                continue;
            }
            try {
                CompoundTag state = new CompoundTag();
                entity.saveWithoutId(state);
                state.putInt("Version", readDataVersion(entity));

                FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
                byte[] bytes;
                try {
                    encoded.writeVarInt(current.entityDataPacketId());
                    encoded.writeInt(entity.getId());
                    encoded.writeNbt(state);
                    bytes = new byte[encoded.readableBytes()];
                    encoded.getBytes(encoded.readerIndex(), bytes);
                } finally {
                    encoded.release();
                }

                ActionModdedPayload.EncodedPayload payload = new ActionModdedPayload.EncodedPayload(
                        ConnectionProtocol.PLAY, current.channel(), bytes);
                tasks.add(writer -> ActionModdedPayload.write(writer, payload));
            } catch (RuntimeException exception) {
                logOnce("snapshot:" + entity.getType(),
                        "Failed to capture PowerGrid wire " + entity.getId()
                                + " for the replay snapshot", exception);
            }
        }
    }

    public static boolean isUninitializedWire(Entity entity) {
        Protocol current = protocol();
        if (current == null || !current.baseWireClass().isInstance(entity)) {
            return false;
        }
        try {
            return current.getWireEntry().invoke(entity) == null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logOnce("inspect", "Failed to inspect PowerGrid wire initialization", exception);
            // Retain the entity instead of allowing PowerGrid's known serialization NPE to terminate
            // the replay server when compatibility inspection itself fails.
            return true;
        }
    }

    public static boolean isWire(Entity entity) {
        Protocol current = protocol();
        return current != null && current.baseWireClass().isInstance(entity);
    }

    /** Validates on a disposable entity before mutating the entity Flashback will track. */
    public static void applyEntityData(Entity entity, CompoundTag data) {
        Protocol current = protocol();
        if (current == null || !current.baseWireClass().isInstance(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not a PowerGrid wire");
        }

        Entity probe = entity.getType().create(entity.level());
        if (probe == null || !current.baseWireClass().isInstance(probe)) {
            throw new IllegalStateException("Could not create a PowerGrid validation entity for "
                    + entity.getType());
        }
        probe.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());

        invokeEntityData(current, probe, data.copy());
        if (isUninitializedWire(probe)) {
            throw new IllegalStateException("PowerGrid entity data did not initialize " + entity.getType());
        }
        // This invokes PowerGrid's serializer and catches incomplete endpoint/item state before the
        // real entity can be inserted into the ReplayServer and paired to its viewer.
        probe.saveWithoutId(new CompoundTag());

        invokeEntityData(current, entity, data.copy());
        if (isUninitializedWire(entity)) {
            throw new IllegalStateException("PowerGrid entity data left " + entity.getType()
                    + " uninitialized");
        }
    }

    private static void invokeEntityData(Protocol protocol, Entity entity, CompoundTag data) {
        try {
            protocol.onEntityDataPacket().invoke(entity, data);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("PowerGrid rejected recorded wire data", cause);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Could not apply recorded PowerGrid wire data", exception);
        }
    }

    private static int readDataVersion(Entity entity) {
        Class<?> type = entity.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("dataVersion");
                field.setAccessible(true);
                return field.getInt(entity);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logOnce("version", "Could not read PowerGrid's wire data version; using zero", exception);
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Protocol protocol() {
        Protocol current = protocol;
        if (current != null || unavailable) {
            return current;
        }
        synchronized (PowerGridReplayCompat.class) {
            current = protocol;
            if (current != null || unavailable) {
                return current;
            }
            try {
                Class<?> packetsClass = Class.forName(PACKETS_CLASS);
                Object packetSet = packetsClass.getField("PACKETS").get(null);
                ResourceLocation channel = (ResourceLocation) packetSet.getClass()
                        .getField("s2cPacket").get(packetSet);

                Class<?> entityDataClass = Class.forName(ENTITY_DATA_CLASS);
                Field typesField = findField(packetSet.getClass(), "s2cTypes");
                typesField.setAccessible(true);
                Object2IntMap<Object> types = (Object2IntMap<Object>) typesField.get(packetSet);
                if (!types.containsKey(entityDataClass)) {
                    throw new IllegalStateException("PowerGrid did not register EntityDataS2CPacket");
                }

                Class<?> baseWireClass = Class.forName(BASE_WIRE_CLASS);
                Method onEntityDataPacket = baseWireClass.getMethod(
                        "onEntityDataPacket", CompoundTag.class);
                Method getWireEntry = baseWireClass.getMethod("getWireEntry");
                current = new Protocol(channel, types.getInt(entityDataClass), baseWireClass,
                        onEntityDataPacket, getWireEntry);
                protocol = current;
                return current;
            } catch (ClassNotFoundException ignored) {
                unavailable = true;
                return null;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                unavailable = true;
                logOnce("protocol", "PowerGrid is present but its replay protocol could not be inspected",
                        exception);
                return null;
            }
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void logOnce(String key, String message) {
        if (LOGGED_FAILURES.add(key)) {
            LOGGER.warn(message);
        }
    }

    private static void logOnce(String key, String message, Throwable throwable) {
        if (LOGGED_FAILURES.add(key)) {
            LOGGER.warn(message, throwable);
        }
    }

    public record EntityData(int entityId, CompoundTag data) {
    }

    private record Protocol(
            ResourceLocation channel,
            int entityDataPacketId,
            Class<?> baseWireClass,
            Method onEntityDataPacket,
            Method getWireEntry) {
    }
}
