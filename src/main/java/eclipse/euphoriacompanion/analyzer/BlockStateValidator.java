package eclipse.euphoriacompanion.analyzer;

import cpw.mods.fml.common.registry.GameRegistry;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.block.Block;
import net.minecraft.util.IIcon;

import java.util.*;

/**
 * Validates that block definitions with specific metadata values cover all possible metadata variants.
 * Adapted for Minecraft 1.7.10 metadata system (0-15 values instead of BlockState properties)
 */
public class BlockStateValidator {

    /**
     * Parses a block+metadata string for 1.7.10
     * Format: "modName:blockName:metadata" or "blockName:metadata" (vanilla)
     * Returns null if no metadata specified
     */
    public static BlockMetadataSpec parseBlockMetadata(String fullBlockId) {
        String[] segments = fullBlockId.split(":");

        if (segments.length < 2) {
            return null;
        }

        String namespace;
        String blockName;
        String metadataStr;

        if (segments.length == 2) {
            namespace = "minecraft";
            blockName = segments[0];
            metadataStr = segments[1];
        } else if (segments.length == 3) {
            namespace = segments[0];
            blockName = segments[1];
            metadataStr = segments[2];
        } else {
            return null;
        }

        int metadata;
        try {
            metadata = Integer.parseInt(metadataStr);
            if (metadata < 0 || metadata > 15) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }

        String blockId = namespace + ":" + blockName;
        return new BlockMetadataSpec(blockId, metadata);
    }

    /**
     * Validates metadata completeness and returns categorized missing metadata values
     * Returns: Map<blockId, CategorizedMetadata>
     */
    public static Map<String, CategorizedMetadata> validateBlockMetadata(Map<String, Integer> blockToProperty) {
        Map<String, Set<Integer>> definedMetadataByBlock = new HashMap<>();

        for (String fullId : blockToProperty.keySet()) {
            BlockMetadataSpec spec = parseBlockMetadata(fullId);
            if (spec == null) {
                continue;
            }

            String blockId = spec.getBlockId();
            Set<Integer> metadataValues = definedMetadataByBlock.computeIfAbsent(blockId, k -> new HashSet<>());

            metadataValues.add(spec.getMetadata());
        }

        Map<String, CategorizedMetadata> incompleteMetadata = new TreeMap<>();

        for (Map.Entry<String, Set<Integer>> entry : definedMetadataByBlock.entrySet()) {
            String blockId = entry.getKey();
            Set<Integer> definedMetadata = entry.getValue();

            CategorizedMetadata categorized = getCategorizedMetadata(blockId, definedMetadata);

            if (!categorized.isEmpty()) {
                incompleteMetadata.put(blockId, categorized);
            }
        }

        return incompleteMetadata;
    }

    /**
     * Gets categorized missing metadata values for a block
     */
    private static CategorizedMetadata getCategorizedMetadata(String blockId, Set<Integer> definedMetadata) {
        List<Integer> visuallyDistinct = new ArrayList<>();
        Map<Integer, List<Integer>> duplicatesOfDefined = new TreeMap<>();
        List<Integer> potentiallyDistinct = new ArrayList<>();

        try {
            String[] parts = blockId.split(":", 2);
            if (parts.length != 2) {
                return new CategorizedMetadata(visuallyDistinct, duplicatesOfDefined, potentiallyDistinct);
            }

            String modId = parts[0];
            String blockName = parts[1];

            Block block = GameRegistry.findBlock(modId, blockName);
            if (block == null) {
                return new CategorizedMetadata(visuallyDistinct, duplicatesOfDefined, potentiallyDistinct);
            }

            // Get icon signatures for all metadata
            // We validate all metadata 0-15 for blocks that have ANY defined metadata,
            // regardless of item.getHasSubtypes(), because blocks like farmland have metadata
            // (moisture levels) even though the item doesn't have subtypes
            Map<String, Integer> definedIconToMeta = new HashMap<>();
            for (Integer definedMeta : definedMetadata) {
                String signature = getIconSignature(block, definedMeta);
                if (!definedIconToMeta.containsKey(signature)) {
                    definedIconToMeta.put(signature, definedMeta);
                }
            }

            Map<String, List<Integer>> missingIconToMetas = new HashMap<>();

            for (int meta = 0; meta < 16; meta++) {
                if (definedMetadata.contains(meta)) {
                    continue;
                }

                String signature = getIconSignature(block, meta);

                if (definedIconToMeta.containsKey(signature)) {
                    Integer matchingDefinedMeta = definedIconToMeta.get(signature);
                    List<Integer> duplicates = duplicatesOfDefined.computeIfAbsent(matchingDefinedMeta, k -> new ArrayList<>());
                    duplicates.add(meta);
                } else {
                    List<Integer> metasWithSignature = missingIconToMetas.computeIfAbsent(signature, k -> new ArrayList<>());
                    metasWithSignature.add(meta);
                }
            }

            for (List<Integer> metasWithSignature : missingIconToMetas.values()) {
                if (metasWithSignature.size() == 1) {
                    visuallyDistinct.add(metasWithSignature.get(0));
                } else {
                    potentiallyDistinct.addAll(metasWithSignature);
                }
            }

            Collections.sort(visuallyDistinct);
            Collections.sort(potentiallyDistinct);

            for (List<Integer> duplicates : duplicatesOfDefined.values()) {
                Collections.sort(duplicates);
            }

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to categorize metadata for: {}", blockId, e);
        }

        return new CategorizedMetadata(visuallyDistinct, duplicatesOfDefined, potentiallyDistinct);
    }

    /**
     * Gets an icon signature for a block+metadata combination
     * Returns a string representing the icons for all 6 sides
     */
    private static String getIconSignature(Block block, int metadata) {
        StringBuilder signature = new StringBuilder();

        // Check all 6 sides (0-5: down, up, north, south, west, east)
        for (int side = 0; side < 6; side++) {
            try {
                IIcon icon = block.getIcon(side, metadata);
                String iconId = (icon != null) ? icon.getIconName() : "null";
                signature.append(side).append(":").append(iconId).append(";");
            } catch (Exception e) {
                signature.append(side).append(":error;");
            }
        }

        return signature.toString();
    }

    /**
     * Represents a parsed block+metadata specification for 1.7.10
     * Format: "namespace:blockname:metadata"
     */
    public static class BlockMetadataSpec {
        private final String blockId;
        private final int metadata;

        public BlockMetadataSpec(String blockId, int metadata) {
            this.blockId = blockId;
            this.metadata = metadata;
        }

        public String getBlockId() {
            return blockId;
        }

        public int getMetadata() {
            return metadata;
        }
    }

    /**
     * Represents categorized missing metadata values
     */
    public static class CategorizedMetadata {
        private final List<Integer> visuallyDistinct;     // Different icons from all defined metadata
        private final Map<Integer, List<Integer>> duplicatesOfDefined;  // Maps defined meta -> list of duplicates
        private final List<Integer> potentiallyDistinct;  // Same icons as each other, not in defined

        public CategorizedMetadata(List<Integer> visuallyDistinct,
                                   Map<Integer, List<Integer>> duplicatesOfDefined,
                                   List<Integer> potentiallyDistinct) {
            this.visuallyDistinct = visuallyDistinct;
            this.duplicatesOfDefined = duplicatesOfDefined;
            this.potentiallyDistinct = potentiallyDistinct;
        }

        public List<Integer> getVisuallyDistinct() {
            return visuallyDistinct;
        }

        public Map<Integer, List<Integer>> getDuplicatesOfDefined() {
            return duplicatesOfDefined;
        }

        public List<Integer> getPotentiallyDistinct() {
            return potentiallyDistinct;
        }

        public boolean isEmpty() {
            return visuallyDistinct.isEmpty() && duplicatesOfDefined.isEmpty() && potentiallyDistinct.isEmpty();
        }
    }
}