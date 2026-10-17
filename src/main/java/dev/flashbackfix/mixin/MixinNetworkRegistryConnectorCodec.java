package dev.flashbackfix.mixin;

import dev.flashbackfix.compat.ConnectorPayloadCodecCompat;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes Connector's Fabric codec substitution independent of NeoForge return-value ordinals. */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class MixinNetworkRegistryConnectorCodec {

    @Inject(method = "getCodec", at = @At("RETURN"), cancellable = true)
    private static void flashbackNeoForgeFixed$restoreConnectorFabricCodec(
            ResourceLocation id,
            ConnectionProtocol protocol,
            PacketFlow flow,
            CallbackInfoReturnable<StreamCodec<? super FriendlyByteBuf,
                    ? extends CustomPacketPayload>> cir) {
        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> original =
                cir.getReturnValue();
        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> corrected =
                ConnectorPayloadCodecCompat.replaceDummyCodec(original, id, protocol, flow);
        if (corrected != original) {
            cir.setReturnValue(corrected);
        }
    }
}
