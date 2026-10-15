package dev.flashbackfix.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Repairs IV handshake-to-wrapper pairing after Flashback reconstructs an entity snapshot. */
public final class ImmersiveVehiclesHandshakeCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final String BUILDER_BASE = "mcinterface1211.ABuilderEntityBase";
    private static final String WRAPPER_NBT = "mcinterface1211.WrapperNBT";

    private static volatile Reflection reflection;
    private static volatile boolean unavailable;
    private static ClientLevel pendingLevel;
    private static final List<PendingHandshake> pendingHandshakes = new ArrayList<>();

    private ImmersiveVehiclesHandshakeCompat() {
    }

    /**
     * MTS addresses its full entity-state handshake by the vanilla wrapper's UUID. Flashback may
     * reconstruct the wrapper under a different UUID while retaining its numeric replay entity ID.
     * MTS then silently drops the otherwise complete handshake, so its internal vehicle never
     * enters the renderable-entity manager.
     *
     * <p>Run after MTS' normal handler. An exact UUID match remains preferred. If it no longer
     * exists, retain the snapshot until that wrapper begins its first client tick. Flashback sends
     * custom snapshot actions before its ordinary entity tracker has necessarily created the target
     * on the physical client, so treating a missing wrapper as a permanent miss loses every IV
     * vehicle in the snapshot.</p>
     */
    public static void finishReplayHandshake(Object handshakePacket) {
        if (!ReplayStateCompat.isInReplay()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;
        Reflection api = reflection();
        if (level == null || api == null) {
            return;
        }

        try {
            String builderId = (String) api.builderId().get(handshakePacket);
            Object wrappedNbt = api.data().get(handshakePacket);
            if (builderId == null || wrappedNbt == null
                    || !api.wrapperNbt().isInstance(wrappedNbt)) {
                return;
            }
            CompoundTag tag = ((CompoundTag) api.tag().get(wrappedNbt)).copy();
            String expectedBuilder = expectedBuilder(tag);
            if (expectedBuilder == null) {
                return;
            }

            Entity exact = null;
            for (Entity entity : level.entitiesForRendering()) {
                if (entity.isRemoved() || !api.builderBase().isInstance(entity)
                        || !entity.getClass().getSimpleName().equals(expectedBuilder)) {
                    continue;
                }
                if (entity.getStringUUID().equals(builderId)) {
                    exact = entity;
                    break;
                }
            }

            if (exact == null) {
                queue(level, new PendingHandshake(builderId, expectedBuilder, tag,
                        System.nanoTime()));
                return;
            }
            apply(api, exact, tag);
        } catch (IllegalAccessException | RuntimeException exception) {
            LOGGER.error("Failed to finish an Immersive Vehicles replay handshake", exception);
        }
    }

    /** Applies a snapshot that arrived before this IV wrapper was tracked by the replay client. */
    public static void applyPendingHandshake(Entity builder) {
        if (!ReplayStateCompat.isInReplay() || builder == null || builder.isRemoved()) {
            return;
        }
        Reflection api = reflection();
        if (api == null || !api.builderBase().isInstance(builder)) {
            return;
        }

        PendingHandshake selected = null;
        synchronized (pendingHandshakes) {
            if (pendingLevel != builder.level()) {
                pendingHandshakes.clear();
                pendingLevel = builder.level() instanceof ClientLevel clientLevel
                        ? clientLevel : null;
                return;
            }
            String kind = builder.getClass().getSimpleName();
            for (PendingHandshake pending : pendingHandshakes) {
                if (pending.expectedBuilder().equals(kind)
                        && pending.builderId().equals(builder.getStringUUID())) {
                    selected = pending;
                    break;
                }
            }

            // UUIDs normally survive the replay add-entity packet. Keep a position fallback for
            // recordings where another mod replaced that packet, but wait one second so all exact
            // candidates have a chance to enter the client level first.
            if (selected == null) {
                long now = System.nanoTime();
                double bestDistance = Double.POSITIVE_INFINITY;
                for (PendingHandshake pending : pendingHandshakes) {
                    if (!pending.expectedBuilder().equals(kind)
                            || now - pending.queuedAtNanos() < 1_000_000_000L) {
                        continue;
                    }
                    CompoundTag tag = pending.tag();
                    boolean positioned = tag.contains("positionx")
                            || tag.contains("positiony") || tag.contains("positionz");
                    double distance = positioned
                            ? builder.distanceToSqr(tag.getDouble("positionx"),
                                    tag.getDouble("positiony"), tag.getDouble("positionz"))
                            : builder.getId();
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        selected = pending;
                    }
                }
            }
            if (selected != null) {
                pendingHandshakes.remove(selected);
            }
        }

        if (selected != null) {
            try {
                apply(api, builder, selected.tag());
            } catch (IllegalAccessException | RuntimeException exception) {
                LOGGER.error("Failed to apply a deferred Immersive Vehicles replay handshake",
                        exception);
            }
        }
    }

    private static void queue(ClientLevel level, PendingHandshake pending) {
        synchronized (pendingHandshakes) {
            if (pendingLevel != level) {
                pendingHandshakes.clear();
                pendingLevel = level;
            }
            pendingHandshakes.removeIf(existing ->
                    existing.builderId().equals(pending.builderId()));
            pendingHandshakes.add(pending);
        }
    }

    private static void apply(Reflection api, Entity target, CompoundTag tag)
            throws IllegalAccessException {
        // Repeat the assignment even on an exact match: this also repairs an MTS wrapper-cache miss
        // in its original handler without changing behavior in an ordinary world. The caller that
        // runs at ABuilderEntityBase.baseTick HEAD lets IV finish reconstruction in the same tick.
        api.lastLoadedNbt().set(target, tag.copy());
        api.loadedFromSavedNbt().setBoolean(target, false);
        api.loadFromSavedNbt().setBoolean(target, true);
    }

    private static String expectedBuilder(CompoundTag tag) {
        if (tag.contains("entityid")) {
            return "BuilderEntityExisting";
        }
        if (tag.contains("playerFollowing")) {
            return "BuilderEntityRenderForwarder";
        }
        if (tag.contains("entityUUID")) {
            return "BuilderEntityLinkedSeat";
        }
        return null;
    }

    private static Reflection reflection() {
        Reflection cached = reflection;
        if (cached != null || unavailable) {
            return cached;
        }
        synchronized (ImmersiveVehiclesHandshakeCompat.class) {
            if (reflection != null || unavailable) {
                return reflection;
            }
            try {
                ClassLoader loader = ImmersiveVehiclesHandshakeCompat.class.getClassLoader();
                Class<?> packet = Class.forName(
                        "mcinterface1211.PacketEntityCSHandshakeServer", false, loader);
                Class<?> builderBase = Class.forName(BUILDER_BASE, false, loader);
                Class<?> wrapperNbt = Class.forName(WRAPPER_NBT, false, loader);

                Field builderId = accessibleField(packet, "builderID");
                Field data = accessibleField(packet, "data");
                Field tag = accessibleField(wrapperNbt, "tag");
                Field lastLoadedNbt = accessibleField(builderBase, "lastLoadedNBT");
                Field loadFromSavedNbt = accessibleField(builderBase, "loadFromSavedNBT");
                Field loadedFromSavedNbt = accessibleField(builderBase, "loadedFromSavedNBT");
                reflection = new Reflection(builderBase, wrapperNbt, builderId, data, tag,
                        lastLoadedNbt, loadFromSavedNbt, loadedFromSavedNbt);
            } catch (ClassNotFoundException exception) {
                unavailable = true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                unavailable = true;
                LOGGER.error("Immersive Vehicles replay handshake compatibility could not initialize",
                        exception);
            }
            return reflection;
        }
    }

    private static Field accessibleField(Class<?> owner, String name)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record Reflection(
            Class<?> builderBase,
            Class<?> wrapperNbt,
            Field builderId,
            Field data,
            Field tag,
            Field lastLoadedNbt,
            Field loadFromSavedNbt,
            Field loadedFromSavedNbt) {
    }

    private record PendingHandshake(
            String builderId,
            String expectedBuilder,
            CompoundTag tag,
            long queuedAtNanos) {
    }
}
