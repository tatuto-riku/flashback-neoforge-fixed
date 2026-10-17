package dev.flashbackfix.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Bridges a Connector networking bug caused by NeoForge adding a return path to
 * {@code NetworkRegistry#getCodec}.
 *
 * <p>Connector registers Fabric payload channels in NeoForge with a sentinel codec, then replaces
 * that sentinel with Fabric's real codec from an ordinal-specific return-value injection. Newer
 * NeoForge builds added another early return, shifting the ordinal and leaving the sentinel in
 * place. Its encoder intentionally throws {@link UnsupportedOperationException}, disconnecting an
 * otherwise valid local or dedicated-server connection as soon as a Fabric payload is sent.</p>
 *
 * <p>All Connector references are reflective so this mod continues to load without Connector or
 * Forgified Fabric API installed.</p>
 */
public final class ConnectorPayloadCodecCompat {

    private static final String REGISTRAR_CLASS =
            "org.sinytra.fabric.networking_api.NeoNetworkRegistrar";

    private static volatile Lookup lookup;
    private static volatile boolean lookupAttempted;

    private ConnectorPayloadCodecCompat() {
    }

    @SuppressWarnings("unchecked")
    public static StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> replaceDummyCodec(
            StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec,
            ResourceLocation id,
            ConnectionProtocol protocol,
            PacketFlow flow) {
        if (codec == null) {
            return null;
        }

        Lookup current = getLookup();
        if (current == null || codec != current.dummyCodec) {
            return codec;
        }

        try {
            Object registry = current.getPayloadRegistry.invoke(null, protocol, flow);
            Object typeAndCodec = current.getPayloadType.invoke(registry, id);
            if (typeAndCodec == null) {
                return codec;
            }
            Object fabricCodec = current.getCodec.invoke(typeAndCodec);
            return fabricCodec instanceof StreamCodec<?, ?>
                    ? (StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload>) fabricCodec
                    : codec;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Keep NeoForge's original result if Connector changes its internal API. This method is
            // on the network codec lookup path, so compatibility detection must never break packets.
            return codec;
        }
    }

    private static Lookup getLookup() {
        if (!lookupAttempted) {
            synchronized (ConnectorPayloadCodecCompat.class) {
                if (!lookupAttempted) {
                    lookup = createLookup();
                    lookupAttempted = true;
                }
            }
        }
        return lookup;
    }

    private static Lookup createLookup() {
        try {
            Class<?> registrarClass = Class.forName(REGISTRAR_CLASS, false,
                    ConnectorPayloadCodecCompat.class.getClassLoader());
            Field dummyCodecField = registrarClass.getField("DUMMY_CODEC");
            Method getPayloadRegistry = registrarClass.getMethod(
                    "getPayloadRegistry", ConnectionProtocol.class, PacketFlow.class);

            Class<?> registryClass = Class.forName(
                    "net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false,
                    ConnectorPayloadCodecCompat.class.getClassLoader());
            Method getPayloadType = registryClass.getMethod("get", ResourceLocation.class);
            Method getCodec = CustomPacketPayload.TypeAndCodec.class.getMethod("codec");
            return new Lookup(dummyCodecField.get(null), getPayloadRegistry, getPayloadType, getCodec);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private record Lookup(
            Object dummyCodec,
            Method getPayloadRegistry,
            Method getPayloadType,
            Method getCodec) {
    }
}
