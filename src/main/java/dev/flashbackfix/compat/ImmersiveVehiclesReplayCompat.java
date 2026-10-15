package dev.flashbackfix.compat;

import com.moulberry.flashback.io.ReplayWriter;
import dev.flashbackfix.action.ActionModdedPayload;
import dev.flashbackfix.action.ActionModdedSnapshotPayload;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures the full client-side state used by Immersive Vehicles' handshake protocol. */
public final class ImmersiveVehiclesReplayCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final String BUILDER_ENTITY = "mcinterface1211.ABuilderEntityBase";
    private static final String WRAPPER_NBT = "mcinterface1211.WrapperNBT";
    private static final String WRAPPER_NBT_INTERFACE =
            "minecrafttransportsimulator.mcinterface.IWrapperNBT";
    private static final String HANDSHAKE = "mcinterface1211.PacketEntityCSHandshakeServer";
    private static final String PACKET_BASE =
            "minecrafttransportsimulator.packets.components.APacketBase";
    private static final String WRAPPER_PAYLOAD = "mcinterface1211.InterfacePacket$WrapperPayload";

    private static volatile Reflection reflection;
    private static volatile boolean unavailable;

    private ImmersiveVehiclesReplayCompat() {
    }

    /**
     * Appends one server-handshake image for each MTS wrapper entity.
     *
     * <p>MTS intentionally spawns an almost empty vanilla entity and asks the server for the actual
     * vehicle NBT on its next client tick. A replay server has no MTS vehicle object with which to
     * answer that request. Capture the same reply directly from the already initialized live client
     * entity and place it after the vanilla add-entity snapshot.</p>
     */
    public static void appendSnapshotTasks(
            ClientLevel level, List<Consumer<ReplayWriter>> tasks) {
        Reflection api = reflection();
        if (api == null) {
            return;
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || !api.builderEntity().isInstance(entity)) {
                continue;
            }
            try {
                CompoundTag tag = entity.saveWithoutId(new CompoundTag());
                Object wrappedNbt = api.wrapperNbt().newInstance(tag.copy());
                Object handshake = api.handshake().newInstance(entity, wrappedNbt);
                Object wrapper = api.wrapperPayload().newInstance(handshake);
                if (!(wrapper instanceof CustomPacketPayload payload)) {
                    continue;
                }
                ActionModdedPayload.EncodedPayload encoded =
                        ActionModdedPayload.encodeSynthetic(ConnectionProtocol.PLAY, payload);
                if (encoded != null) {
                    tasks.add(writer -> ActionModdedSnapshotPayload.write(writer, encoded));
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.error("Failed to capture Immersive Vehicles snapshot for entity {} ({})",
                        entity.getId(), entity.getType(), exception);
            }
        }
    }

    private static Reflection reflection() {
        Reflection cached = reflection;
        if (cached != null || unavailable) {
            return cached;
        }
        synchronized (ImmersiveVehiclesReplayCompat.class) {
            if (reflection != null || unavailable) {
                return reflection;
            }
            try {
                ClassLoader loader = ImmersiveVehiclesReplayCompat.class.getClassLoader();
                Class<?> builderEntity = Class.forName(BUILDER_ENTITY, false, loader);
                Class<?> wrapperNbtClass = Class.forName(WRAPPER_NBT, false, loader);
                Class<?> wrapperNbtInterface = Class.forName(WRAPPER_NBT_INTERFACE, false, loader);
                Class<?> handshakeClass = Class.forName(HANDSHAKE, false, loader);
                Class<?> packetBase = Class.forName(PACKET_BASE, false, loader);
                Class<?> wrapperPayloadClass = Class.forName(WRAPPER_PAYLOAD, false, loader);

                Constructor<?> wrapperNbt = wrapperNbtClass.getDeclaredConstructor(CompoundTag.class);
                wrapperNbt.setAccessible(true);
                Constructor<?> handshake = handshakeClass.getConstructor(
                        builderEntity, wrapperNbtInterface);
                Constructor<?> wrapperPayload = wrapperPayloadClass.getConstructor(packetBase);
                reflection = new Reflection(builderEntity, wrapperNbt, handshake, wrapperPayload);
            } catch (ClassNotFoundException exception) {
                // Optional compatibility: the MTS classes simply do not exist in most packs.
                unavailable = true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                unavailable = true;
                LOGGER.error("Immersive Vehicles replay compatibility could not be initialized",
                        exception);
            }
            return reflection;
        }
    }

    private record Reflection(
            Class<?> builderEntity,
            Constructor<?> wrapperNbt,
            Constructor<?> handshake,
            Constructor<?> wrapperPayload) {
    }
}
