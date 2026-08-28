package dev.flashbackfix.compat;

import dev.flashbackfix.mixin.AccessorEntityInterpolation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

/** Prevents network-lag compensation from becoming a second replay timeline. */
public final class SmoothMovementReplayCompat {

    private static boolean replayWasActive;
    private static boolean clientStateCaptured;
    private static boolean changedClientSmoothing;
    private static boolean originalClientSmoothing;
    private static Object commonConfig;
    private static Field livingSmoothingField;
    private static long nextClientResolveAttempt;
    private static Field slownessFactorField;
    private static Field averageSlownessFactorField;
    private static Field extraTicksField;
    private static Field extraTickTotalField;

    private SmoothMovementReplayCompat() {
    }

    /** Called every rendered frame and immediately before replay teleports are applied. */
    public static synchronized void updateClientState() {
        if (!ModList.get().isLoaded("smoothmovement")) {
            return;
        }

        boolean replayActive = ReplayStateCompat.isInReplay();
        if (replayActive) {
            disableClientSmoothing();
        } else if (replayWasActive) {
            restoreClientSmoothing();
        }
        replayWasActive = replayActive;
    }

    /** Final safety net after all teleport handlers, including Smooth Movement's TAIL injector. */
    public static void clampTeleportInterpolation(Entity entity) {
        if (!ReplayStateCompat.isInReplay() || !(entity instanceof LivingEntity)) {
            return;
        }
        AccessorEntityInterpolation interpolation = (AccessorEntityInterpolation) entity;
        if (interpolation.flashbackNeoForgeFixed$getLerpSteps() != 3) {
            interpolation.flashbackNeoForgeFixed$setLerpSteps(3);
        }
    }

    /**
     * Smooth Movement derives a process-global speed multiplier from server tick duration. A replay
     * is a deterministic recorded timeline, so measuring the fake server and scaling its physics a
     * second time is invalid. Reset any multiplier inherited from the previous server as well.
     */
    public static boolean suppressServerTimingDuringReplay() {
        if (!ReplayStateCompat.isInReplay()) {
            return false;
        }
        try {
            resolveServerTimeFields();
            slownessFactorField.setFloat(null, 1.0F);
            averageSlownessFactorField.setFloat(null, 1.0F);
            extraTicksField.setInt(null, 0);
            extraTickTotalField.setDouble(null, 0.0D);
        } catch (ReflectiveOperationException | LinkageError exception) {
            // Optional compatibility: unsupported versions keep their original behavior.
        }
        return true;
    }

    private static void disableClientSmoothing() {
        if (!clientStateCaptured) {
            long now = System.currentTimeMillis();
            if (now < nextClientResolveAttempt) {
                return;
            }
            try {
                resolveClientConfig();
                originalClientSmoothing = livingSmoothingField.getBoolean(commonConfig);
                clientStateCaptured = true;
            } catch (ReflectiveOperationException | LinkageError exception) {
                nextClientResolveAttempt = now + 1000L;
                return;
            }
        }

        try {
            if (livingSmoothingField.getBoolean(commonConfig)) {
                livingSmoothingField.setBoolean(commonConfig, false);
                changedClientSmoothing = true;
            }
        } catch (IllegalAccessException exception) {
            nextClientResolveAttempt = System.currentTimeMillis() + 1000L;
            clientStateCaptured = false;
        }
    }

    private static void restoreClientSmoothing() {
        if (clientStateCaptured && changedClientSmoothing) {
            try {
                livingSmoothingField.setBoolean(commonConfig, originalClientSmoothing);
            } catch (IllegalAccessException exception) {
                // The optional mod may have changed its config implementation while running.
            }
        }
        clientStateCaptured = false;
        changedClientSmoothing = false;
        commonConfig = null;
        livingSmoothingField = null;
        nextClientResolveAttempt = 0L;
    }

    private static void resolveClientConfig() throws ReflectiveOperationException {
        Class<?> configuration = Class.forName("com.smoothmovement.config.CommonConfiguration");
        Field configField = configuration.getDeclaredField("config");
        configField.setAccessible(true);
        Object cupboardConfig = configField.get(null);
        if (cupboardConfig == null) {
            throw new IllegalStateException("Smooth Movement config has not initialized");
        }
        Method getter = cupboardConfig.getClass().getMethod("getCommonConfig");
        commonConfig = getter.invoke(cupboardConfig);
        if (commonConfig == null) {
            throw new IllegalStateException("Smooth Movement common config is unavailable");
        }
        livingSmoothingField = commonConfig.getClass().getField("enableLivingEntitySmoothing");
    }

    private static void resolveServerTimeFields() throws ReflectiveOperationException {
        if (slownessFactorField != null) {
            return;
        }
        Class<?> serverTime = Class.forName("com.smoothmovement.time.ServerTime");
        slownessFactorField = accessible(serverTime, "slownessFactor");
        averageSlownessFactorField = accessible(serverTime, "averageSlownessFactor");
        extraTicksField = accessible(serverTime, "extraTicks");
        extraTickTotalField = accessible(serverTime, "extraTickTotal");
    }

    private static Field accessible(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
