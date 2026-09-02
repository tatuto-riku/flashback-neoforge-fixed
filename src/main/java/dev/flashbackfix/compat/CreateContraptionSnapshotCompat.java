package dev.flashbackfix.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Preserves client update tags when a Create contraption is serialized into a replay snapshot. */
public final class CreateContraptionSnapshotCompat {

    private static final String CREATE_CONTRAPTION =
            "com.simibubi.create.content.contraptions.Contraption";

    private CreateContraptionSnapshotCompat() {
    }

    /**
     * Restores the round-trip information which Create discards after reading a spawn packet.
     *
     * <p>On the first server-to-client sync, Create stores each block entity's network update tag in
     * {@link StructureBlockInfo#nbt()}, leaves its {@code updateTags} map empty, and marks the block
     * as non-legacy. If that client entity is serialized again for a Flashback snapshot, Create sees
     * the empty map and writes the same network tag with a {@code Legacy} marker. The next client then
     * loads it as disk NBT instead of calling {@code handleUpdateTag}. Mods whose disk and network
     * formats differ, including FramedBlocks camo data, consequently lose their visual state.</p>
     *
     * <p>Copying non-legacy tags back into Create's update-tag map restores the original packet
     * semantics without interpreting any mod-specific NBT. Recordings produced before this fix can
     * already contain the incorrect legacy marker. Those are repaired only when the tag lacks the
     * block-entity type id which {@code saveWithFullMetadata} always writes; genuine disk tags keep
     * their marker.</p>
     */
    public static void prepareForComplexSpawnSnapshot(Entity entity) {
        Object contraption = getContraption(entity);
        Class<?> baseClass = findCreateContraptionClass(contraption);
        if (baseClass == null) {
            return;
        }

        try {
            Field blocksField = baseClass.getDeclaredField("blocks");
            Field updateTagsField = baseClass.getDeclaredField("updateTags");
            Field legacyField = baseClass.getDeclaredField("isLegacy");
            blocksField.setAccessible(true);
            updateTagsField.setAccessible(true);
            legacyField.setAccessible(true);

            synchronized (contraption) {
                Object blocksValue = blocksField.get(contraption);
                Object updateTagsValue = updateTagsField.get(contraption);
                Object legacyValue = legacyField.get(contraption);
                if (!(blocksValue instanceof Map<?, ?> blocks)
                        || !(updateTagsValue instanceof Map<?, ?> rawUpdateTags)
                        || !(legacyValue instanceof Map<?, ?> rawLegacy)) {
                    return;
                }

                @SuppressWarnings("unchecked")
                Map<BlockPos, CompoundTag> updateTags =
                        (Map<BlockPos, CompoundTag>) rawUpdateTags;
                @SuppressWarnings("unchecked")
                Map<BlockPos, Boolean> legacy = (Map<BlockPos, Boolean>) rawLegacy;
                for (Map.Entry<?, ?> entry : blocks.entrySet()) {
                    if (!(entry.getKey() instanceof BlockPos pos)
                            || !(entry.getValue() instanceof StructureBlockInfo info)
                            || info.nbt() == null) {
                        continue;
                    }

                    boolean markedLegacy = Boolean.TRUE.equals(legacy.get(pos));
                    if (markedLegacy && info.nbt().contains("id")) {
                        continue;
                    }
                    if (markedLegacy) {
                        legacy.put(pos, false);
                    }
                    updateTags.putIfAbsent(pos, info.nbt().copy());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Create is optional and its internals can differ between versions. In that case retain
            // its unmodified serialization rather than making replay capture fail.
        }
    }

    private static Object getContraption(Entity entity) {
        try {
            Method method = entity.getClass().getMethod("getContraption");
            return method.invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Class<?> findCreateContraptionClass(Object contraption) {
        if (contraption == null) {
            return null;
        }
        Class<?> type = contraption.getClass();
        while (type != null) {
            if (CREATE_CONTRAPTION.equals(type.getName())) {
                return type;
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
