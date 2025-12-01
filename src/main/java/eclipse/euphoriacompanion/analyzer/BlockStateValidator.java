package eclipse.euphoriacompanion.analyzer;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Validates that block definitions with specific blockstates cover all possible values.
 */
public class BlockStateValidator {

    /**
     * Represents a parsed blockstate specification
     *
     * @param blockId    Normalized: "namespace:blockname"
     * @param properties property -> value
     */
        public record BlockStateSpec(String blockId, Map<String, String> properties) {
    }

    /**
     * Parses a blockstate string
     * Format: "modName:blockName:prop1=val1:prop2=val2..." or "blockName:prop1=val1:prop2=val2..." (vanilla)
     */
    public static BlockStateSpec parseBlockState(String fullBlockId) {
        String[] segments = fullBlockId.split(":");

        if (segments.length < 2) {
            return null; // Just a block ID, no blockstates
        }

        String namespace;
        String blockName;
        int propertyStartIndex;

        // Check if second segment contains '=' to determine if first segment is namespace or blockname
        if (segments[1].contains("=")) {
            // Format: "blockName:prop1=val1..."
            namespace = "minecraft";
            blockName = segments[0];
            propertyStartIndex = 1;
        } else {
            // Format: "namespace:blockName:prop1=val1..."
            namespace = segments[0];
            blockName = segments[1];
            propertyStartIndex = 2;
        }

        // Parse properties
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = propertyStartIndex; i < segments.length; i++) {
            String propertyDef = segments[i];
            String[] propParts = propertyDef.split("=", 2);
            if (propParts.length == 2) {
                properties.put(propParts[0], propParts[1]);
            }
        }

        if (properties.isEmpty()) {
            return null; // No blockstate properties defined
        }

        String blockId = namespace + ":" + blockName;
        return new BlockStateSpec(blockId, properties);
    }

    /**
     * Validates blockstate completeness and returns missing property values
     * Returns: Map<blockId, Map<propertyName, List<missingValues>>>
     */
    public static Map<String, Map<String, List<String>>> validateBlockStates(Map<String, Integer> blockToProperty) {
        // Group specs by block ID and track which property values are defined
        Map<String, Map<String, Set<String>>> definedValuesByBlock = new HashMap<>();

        for (String fullId : blockToProperty.keySet()) {
            BlockStateSpec spec = parseBlockState(fullId);
            if (spec == null) {
                continue; // No blockstates defined
            }

            String blockId = spec.blockId();
            Map<String, Set<String>> propertyValues = definedValuesByBlock.computeIfAbsent(
                blockId, k -> new HashMap<>()
            );

            // Track which values are defined for each property
            for (Map.Entry<String, String> prop : spec.properties().entrySet()) {
                propertyValues.computeIfAbsent(prop.getKey(), k -> new HashSet<>())
                    .add(prop.getValue());
            }
        }

        // For each block, check if all possible values are defined
        Map<String, Map<String, List<String>>> incompleteBlockStates = new TreeMap<>();

        for (Map.Entry<String, Map<String, Set<String>>> entry : definedValuesByBlock.entrySet()) {
            String blockId = entry.getKey();
            Map<String, Set<String>> definedValues = entry.getValue();

            // Check if block exists in registry first
            Identifier id = Identifier.tryParse(blockId);
            if (id == null || !Registries.BLOCK.containsId(id)) {
                // Block doesn't exist in registry (mod not loaded), skip validation
                continue;
            }

            // Get all possible values from Minecraft registry
            Map<String, Set<String>> possibleValues = getPossiblePropertyValues(blockId, definedValues.keySet());

            if (possibleValues.isEmpty()) {
                // Block exists but has no properties matching what's defined - skip
                continue;
            }

            // Compare defined vs possible (case-insensitive)
            Map<String, List<String>> missingByProperty = new TreeMap<>();
            for (Map.Entry<String, Set<String>> propEntry : possibleValues.entrySet()) {
                String propertyName = propEntry.getKey();
                Set<String> allValues = propEntry.getValue();
                Set<String> defined = definedValues.getOrDefault(propertyName, Collections.emptySet());

                // Create lowercase set for case-insensitive comparison
                Set<String> definedLowercase = new HashSet<>();
                for (String val : defined) {
                    definedLowercase.add(val.toLowerCase());
                }

                List<String> missing = new ArrayList<>();
                for (String value : allValues) {
                    if (!definedLowercase.contains(value.toLowerCase())) {
                        missing.add(value);
                    }
                }

                if (!missing.isEmpty()) {
                    Collections.sort(missing);
                    missingByProperty.put(propertyName, missing);
                }
            }

            if (!missingByProperty.isEmpty()) {
                incompleteBlockStates.put(blockId, missingByProperty);
            }
        }

        return incompleteBlockStates;
    }

    /**
     * Gets all possible values for the specified properties of a block
     */
    private static Map<String, Set<String>> getPossiblePropertyValues(String blockId, Set<String> propertyNames) {
        Map<String, Set<String>> possibleValues = new HashMap<>();

        try {
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) {
                return possibleValues;
            }

            if (!Registries.BLOCK.containsId(id)) {
                return possibleValues;
            }

            Block block = Registries.BLOCK.get(id);

            BlockState defaultState = block.getDefaultState();
            if (defaultState == null) {
                return possibleValues;
            }

            // Get all properties of the block
            for (Property<?> property : defaultState.getProperties()) {
                String propertyName = property.getName();

                // Only check properties that are specified in the blockstate definitions
                if (propertyNames.contains(propertyName)) {
                    Set<String> values = new LinkedHashSet<>();
                    for (Object value : property.getValues()) {
                        values.add(value.toString());
                    }
                    possibleValues.put(propertyName, values);
                }
            }

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to get property values for: {}", blockId, e);
        }

        return possibleValues;
    }
}
