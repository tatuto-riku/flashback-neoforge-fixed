package dev.flashbackfix.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Repairs legacy Copycats model data whose block-entity type was decoded as a simple copycat. */
public final class CopycatsLegacyReplayCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("FlashbackNeoForgeFixed");
    private static final String SIMPLE_MATERIAL_KEY = "material";
    private static final String COPYCATS_PACKAGE = "com.copycatsplus.copycats.";
    private static final String COPYCAT_MODEL_CLASS =
            "com.copycatsplus.copycats.foundation.copycat.model.neoforge.CopycatModelNeoForge";

    private CopycatsLegacyReplayCompat() {
    }

    /**
     * Reads Copycats' model-data properties and repairs the simple-material fallback before
     * {@code gatherModelData} starts iterating it. Redirecting the earlier {@code getMaterials}
     * call is intentional: changing the later {@code Map.of("material", ...)} allocation proved
     * too fragile once Connector and the production Mixin pipeline had transformed the method.
     */
    public static Map<?, ?> getReplaySafeMaterials(ModelData data, BlockState state) {
        try {
            Class<?> modelClass = Class.forName(COPYCAT_MODEL_CLASS, false,
                    CopycatsLegacyReplayCompat.class.getClassLoader());
            Field materialsField = modelClass.getField("MATERIALS_PROPERTY");
            Field materialField = modelClass.getField("MATERIAL_PROPERTY");

            Object materials = getModelProperty(data, materialsField.get(null));
            Map<?, ?> materialMap = materials instanceof Map<?, ?> map ? map : Map.of();
            Object simpleMaterial = getModelProperty(data, materialField.get(null));
            return repairMaterialKeys(materialMap, simpleMaterial, state);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException exception) {
            LOGGER.warn("Could not inspect Copycats model data while repairing a replay", exception);
        }
        return Map.of();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object getModelProperty(ModelData data, Object property) {
        if (data == null || !(property instanceof ModelProperty<?> modelProperty)) {
            return null;
        }
        return data.get((ModelProperty) modelProperty);
    }

    /**
     * Copycats' NeoForge model falls back to the single-state {@code MATERIAL_PROPERTY} when a
     * block entity did not provide {@code MATERIALS_PROPERTY}. That fallback uses the key
     * {@code material}, which is not a valid part name for multi-state copycats. Copycat Byte then
     * dereferences the missing part and crashes Sodium's chunk builder.
     *
     * <p>This can occur in recordings made before exact NeoForge registry snapshots were stored:
     * the block state can decode correctly while the numeric block-entity type resolves to the
     * older/simple copycat type. The one recorded material is still usable, so assign it to every
     * part which exists in the decoded block state. Reflection keeps Copycats an optional mod.</p>
     */
    private static Map<?, ?> repairMaterialKeys(
            Map<?, ?> materials, Object simpleMaterial, BlockState state) {
        Block block = state.getBlock();
        Class<?> blockClass = block.getClass();
        if (!blockClass.getName().startsWith(COPYCATS_PACKAGE)) {
            return originalMaterials(materials, simpleMaterial);
        }

        try {
            Method storageProperties;
            try {
                storageProperties = blockClass.getMethod("storageProperties");
            } catch (NoSuchMethodException ignored) {
                // Ordinary one-material Copycats blocks legitimately use the "material" key.
                return originalMaterials(materials, simpleMaterial);
            }
            Object propertyResult = storageProperties.invoke(block);
            if (!(propertyResult instanceof Set<?> properties)
                    || properties.isEmpty()) {
                return originalMaterials(materials, simpleMaterial);
            }

            Method partExists = blockClass.getMethod(
                    "partExists", BlockState.class, String.class);
            Map<String, BlockState> recovered = new LinkedHashMap<>();

            BlockState invalidMaterial = null;
            for (Map.Entry<?, ?> entry : materials.entrySet()) {
                if (!(entry.getValue() instanceof BlockState material)) {
                    continue;
                }
                if (entry.getKey() instanceof String property && properties.contains(property)) {
                    recovered.put(property, material);
                } else if (invalidMaterial == null) {
                    invalidMaterial = material;
                }
            }

            if (invalidMaterial == null && simpleMaterial instanceof BlockState material) {
                invalidMaterial = material;
            }
            if (invalidMaterial == null) {
                return materials;
            }

            for (Object candidate : properties) {
                if (candidate instanceof String property
                        && Boolean.TRUE.equals(partExists.invoke(block, state, property))) {
                    recovered.putIfAbsent(property, invalidMaterial);
                }
            }

            if (recovered.isEmpty()) {
                Method defaultProperty = blockClass.getMethod("defaultProperty");
                Object fallback = defaultProperty.invoke(block);
                if (fallback instanceof String property && properties.contains(property)) {
                    recovered.put(property, invalidMaterial);
                }
            }
            if (recovered.isEmpty()) {
                return originalMaterials(materials, simpleMaterial);
            }
            return recovered;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            LOGGER.warn("Could not expand legacy Copycats material for {}",
                    blockClass.getName(), exception);
            return originalMaterials(materials, simpleMaterial);
        }
    }

    private static Map<?, ?> originalMaterials(Map<?, ?> materials, Object simpleMaterial) {
        if (!materials.isEmpty()) {
            return materials;
        }
        if (simpleMaterial instanceof BlockState material) {
            return Map.of(SIMPLE_MATERIAL_KEY, material);
        }
        return Map.of();
    }
}
