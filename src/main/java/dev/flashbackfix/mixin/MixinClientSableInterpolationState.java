package dev.flashbackfix.mixin;

import dev.flashbackfix.ext.SableInterpolationStateExt;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Resets Sable's global client interpolation clock when Flashback reapplies an older snapshot. */
@Mixin(ClientSableInterpolationState.class)
public class MixinClientSableInterpolationState implements SableInterpolationStateExt {

    @Shadow private double mostRecentTick;
    @Shadow private boolean receivedFirstUpdate;
    @Shadow private double interpolationTick;
    @Shadow private double estimatedServerTickSpeed;
    @Shadow private float serverMsFromLastUpdate;
    @Shadow private boolean stopped;
    @Shadow private PacketReceiveMode receivingMode;
    @Shadow private double latestDelay;
    @Shadow public double mostRecentInterpolationTick;
    @Shadow public double lastInterpolationTick;

    @Override
    public void flashbackNeoForgeFixed$resetForReplaySnapshot() {
        this.mostRecentTick = -1.0;
        this.receivedFirstUpdate = false;
        this.interpolationTick = 0.0;
        this.estimatedServerTickSpeed = 0.0;
        this.serverMsFromLastUpdate = 0.0f;
        this.stopped = true;
        this.receivingMode = PacketReceiveMode.UNKNOWN;
        this.latestDelay = 0.0;
        this.mostRecentInterpolationTick = 0.0;
        this.lastInterpolationTick = 0.0;
    }
}
