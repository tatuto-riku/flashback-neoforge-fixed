package dev.flashbackfix.compat;

import com.moulberry.flashback.playback.ReplayServer;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Makes Flashback's recorded position stream authoritative only for entities which reject vanilla's
 * network interpolation target. Most entities must stay on Minecraft's normal interpolation path;
 * applying a second three-tick interpolator to them causes visible lag and judder. Create's controlled
 * contraptions deliberately implement lerpTo as a no-op because a live controller block entity owns
 * their client position, so those rejected targets are applied after client simulation instead.
 */
public final class ReplayAuthoritativeEntityCompat {

    private static final Map<Integer, ClientPose> CLIENT_POSES = new ConcurrentHashMap<>();
    private static volatile ClientLevel activeLevel;

    private ReplayAuthoritativeEntityCompat() {
    }

    public static void reset() {
        CLIENT_POSES.clear();
        activeLevel = null;
    }

    public static void afterTeleport(ClientLevel level, ClientboundTeleportEntityPacket packet) {
        if (!isActive(level)) {
            return;
        }
        Entity entity = level.getEntity(packet.getId());
        if (entity == null) {
            return;
        }
        Vec3 target = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        float yaw = packet.getyRot() * 360.0F / 256.0F;
        float pitch = packet.getxRot() * 360.0F / 256.0F;
        updateIfRejected(entity, target, yaw, pitch, packet.isOnGround(), true);
    }

    public static void afterMove(ClientLevel level, ClientboundMoveEntityPacket packet) {
        if (!isActive(level)) {
            return;
        }
        Entity entity = packet.getEntity(level);
        if (entity == null) {
            return;
        }

        ClientPose existing = CLIENT_POSES.get(entity.getId());
        if (!packet.hasPosition() && existing == null) {
            return;
        }
        Vec3 target = packet.hasPosition() ? entity.getPositionCodec().getBase()
                : existing != null ? existing.target : entity.position();
        float yaw = packet.hasRotation() ? packet.getyRot() * 360.0F / 256.0F
                : existing != null ? existing.targetYaw : entity.lerpTargetYRot();
        float pitch = packet.hasRotation() ? packet.getxRot() * 360.0F / 256.0F
                : existing != null ? existing.targetPitch : entity.lerpTargetXRot();
        updateIfRejected(entity, target, yaw, pitch, packet.isOnGround(), packet.hasRotation());
    }

    public static void remove(Iterable<Integer> ids) {
        for (int id : ids) {
            CLIENT_POSES.remove(id);
        }
    }

    public static void reconcile(ClientLevel level) {
        if (!isActive(level)) {
            return;
        }
        CLIENT_POSES.entrySet().removeIf(entry -> {
            Entity entity = level.getEntity(entry.getKey());
            ClientPose pose = entry.getValue();
            if (entity == null || entity != pose.entity || entity.isRemoved()) {
                return true;
            }
            // Passenger transforms belong to their vehicle, and locally controlled entities belong
            // to user input. Neither should be driven by the recorded remote-entity stream.
            if (entity.isPassenger() || entity.isControlledByLocalInstance()) {
                return true;
            }
            // Native Create simulation is smooth during uninterrupted forward playback. After a
            // rewind its unrecorded velocity phase cannot be reconstructed, so switch that elevator
            // to Flashback's own per-tick recorded entity transform instead.
            boolean recordedElevator = isCreateElevator(entity)
                    && ReplayCreateElevatorCompat.useRecordedElevatorPosition();
            if (isCreateElevator(entity) && !recordedElevator) {
                return true;
            }
            if (recordedElevator) {
                applySmoothedElevator(entity, pose);
            } else {
                apply(entity, pose);
            }
            return false;
        });
    }

    private static void updateIfRejected(
            Entity entity, Vec3 target, float yaw, float pitch, boolean onGround, boolean hasRotation) {
        if (entity.isPassenger() || entity.isControlledByLocalInstance()) {
            CLIENT_POSES.remove(entity.getId());
            return;
        }
        if (isCreateElevator(entity)
                && !ReplayCreateElevatorCompat.useRecordedElevatorPosition()) {
            CLIENT_POSES.remove(entity.getId());
            return;
        }

        boolean recordedElevator = isCreateElevator(entity)
                && ReplayCreateElevatorCompat.useRecordedElevatorPosition();
        long elevatorSeekEpoch = recordedElevator
                ? ReplayCreateElevatorCompat.recordedElevatorSeekEpoch() : -1;
        ClientPose pose = CLIENT_POSES.get(entity.getId());
        if (pose == null && acceptedTarget(entity, target, yaw, pitch, hasRotation)) {
            // Vanilla already owns this entity's interpolation. Do not create a second interpolator.
            return;
        }
        if (pose == null) {
            pose = new ClientPose(entity, target, yaw, pitch, onGround);
            if (recordedElevator) {
                pose.displayed = target;
                pose.elevatorSeekEpoch = elevatorSeekEpoch;
                pose.snapDisplayed = true;
            }
            CLIENT_POSES.put(entity.getId(), pose);
        } else {
            if (recordedElevator && pose.elevatorSeekEpoch != elevatorSeekEpoch) {
                pose.displayed = target;
                pose.elevatorSeekEpoch = elevatorSeekEpoch;
                pose.snapDisplayed = true;
            }
            pose.target = target;
            pose.targetYaw = yaw;
            pose.targetPitch = pitch;
            pose.onGround = onGround;
        }
    }

    private static boolean acceptedTarget(
            Entity entity, Vec3 target, float yaw, float pitch, boolean hasRotation) {
        boolean acceptedPosition = close(entity.getX(), target.x)
                && close(entity.getY(), target.y)
                && close(entity.getZ(), target.z);
        acceptedPosition |= close(entity.lerpTargetX(), target.x)
                && close(entity.lerpTargetY(), target.y)
                && close(entity.lerpTargetZ(), target.z);
        if (!acceptedPosition) {
            return false;
        }
        return !hasRotation || (closeAngle(entity.lerpTargetYRot(), yaw)
                && closeAngle(entity.lerpTargetXRot(), pitch));
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 1.0E-5;
    }

    private static boolean closeAngle(float first, float second) {
        float difference = (first - second) % 360.0F;
        if (difference < -180.0F) {
            difference += 360.0F;
        } else if (difference >= 180.0F) {
            difference -= 360.0F;
        }
        return Math.abs(difference) < 1.0E-3F;
    }

    private static boolean isCreateElevator(Entity entity) {
        if (!entity.getClass().getName().equals(
                "com.simibubi.create.content.contraptions.ControlledContraptionEntity")) {
            return false;
        }
        try {
            Method method = entity.getClass().getMethod("getContraption");
            Object contraption = method.invoke(entity);
            return contraption != null && contraption.getClass().getName().equals(
                    "com.simibubi.create.content.contraptions.elevator.ElevatorContraption");
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void apply(Entity entity, ClientPose pose) {
        double xo = entity.xo;
        double yo = entity.yo;
        double zo = entity.zo;
        double xOld = entity.xOld;
        double yOld = entity.yOld;
        double zOld = entity.zOld;
        entity.moveTo(pose.target.x, pose.target.y, pose.target.z,
                pose.targetYaw, pose.targetPitch);
        // Entity.moveTo() also rewrites both old-position triplets. Restore them so vanilla and
        // Flywheel can interpolate from the prior displayed point instead of rendering 20 Hz steps.
        entity.xo = xo;
        entity.yo = yo;
        entity.zo = zo;
        entity.xOld = xOld;
        entity.yOld = yOld;
        entity.zOld = zOld;
        entity.setOnGround(pose.onGround);
    }

    private static void applySmoothedElevator(Entity entity, ClientPose pose) {
        Vec3 previousDisplayed = pose.displayed;
        boolean snap = pose.snapDisplayed || previousDisplayed == null;
        Vec3 displayed;
        if (snap) {
            displayed = pose.target;
            pose.snapDisplayed = false;
        } else {
            // The local replay connection can deliver multiple recorded ticks in one burst and then
            // none in the next client tick. Smooth that delivery jitter without ever consulting the
            // live Create destination. This converges to the exact recorded point while paused.
            displayed = pose.displayed.lerp(pose.target, 0.5);
            if (displayed.distanceToSqr(pose.target) < 1.0E-8) {
                displayed = pose.target;
            }
        }
        pose.displayed = displayed;
        Vec3 renderFrom = snap ? displayed : previousDisplayed;
        entity.moveTo(displayed.x, displayed.y, displayed.z,
                pose.targetYaw, pose.targetPitch);
        entity.xo = renderFrom.x;
        entity.yo = renderFrom.y;
        entity.zo = renderFrom.z;
        entity.xOld = renderFrom.x;
        entity.yOld = renderFrom.y;
        entity.zOld = renderFrom.z;
        entity.setOnGround(pose.onGround);
    }

    private static boolean isActive(ClientLevel level) {
        if (level == null || !(Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer)) {
            reset();
            return false;
        }
        if (activeLevel != level) {
            CLIENT_POSES.clear();
            activeLevel = level;
        }
        return true;
    }

    private static final class ClientPose {
        private final Entity entity;
        private volatile Vec3 target;
        private volatile float targetYaw;
        private volatile float targetPitch;
        private volatile boolean onGround;
        private volatile Vec3 displayed;
        private volatile long elevatorSeekEpoch = -1;
        private volatile boolean snapDisplayed;

        private ClientPose(Entity entity, Vec3 target,
                float targetYaw, float targetPitch, boolean onGround) {
            this.entity = entity;
            this.target = target;
            this.targetYaw = targetYaw;
            this.targetPitch = targetPitch;
            this.onGround = onGround;
        }
    }
}
