package eclipse.euphoriacompanion.analyzer;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.config.ModConfig;
import eclipse.euphoriacompanion.parser.BlockPropertiesParser;
import eclipse.euphoriacompanion.report.AnalysisReport;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Main analyzer that orchestrates all phases of shader compatibility analysis.
 */
public record ShaderAnalyzer(ModConfig config, int currentMCVersion) {

    /**
     * Analyzes a single shaderpack
     */
    public AnalysisReport analyze(Path shaderpackPath) throws IOException {
        String shaderpackName = shaderpackPath.getFileName().toString();
        EuphoriaCompanion.LOGGER.info("Processing: {}", shaderpackName);

        // Step 1: Parse block.properties
        BlockPropertiesParser parser = parseBlockProperties(shaderpackPath);
        if (parser == null) {
            EuphoriaCompanion.LOGGER.warn("No block.properties found in {}", shaderpackName);
            return new AnalysisReport(shaderpackName);
        }

        // Step 2: Tag Resolution
        Map<String, Set<String>> tagToBlocks = resolveTagsToBlocks(parser);

        // Step 3: Categorize Missing Blocks
        Map<String, Map<String, List<String>>> missingBlocksByMod = categorizeMissingBlocks(
                parser, tagToBlocks);

        // Step 4: Validate BlockStates
        Map<String, Map<String, List<String>>> incompleteBlockStates =
                BlockStateValidator.validateBlockStates(parser.getBlockToProperty());

        // Step 5: Validate Render Layers
        Map<String, RenderLayerMismatch> renderLayerMismatches = validateRenderLayers(parser);

        // Step 6: Get Duplicate Definitions (detected during parsing)
        Map<String, List<Integer>> duplicateDefinitions = parser.getDuplicateBlocks();

        // Step 7: Calculate statistics
        int totalBlocksInGame = calculateTotalBlocksInGame();
        int totalBlocksInShader = calculateTotalBlocksInShader(parser.getBlockToProperty(), tagToBlocks, parser.getTagToProperty());

        // Step 8: Create report (Very nasty I know)
        AnalysisReport report = new AnalysisReport(shaderpackName);
        report.setMissingBlocksByMod(missingBlocksByMod);
        report.setTagCoverage(tagToBlocks);
        report.setTagDefinitions(parser.getTagDefinitions());
        report.setTagToProperty(parser.getTagToProperty());
        report.setRenderLayerMismatches(renderLayerMismatches);
        report.setIncompleteBlockStates(incompleteBlockStates);
        report.setDuplicateDefinitions(duplicateDefinitions);
        report.setTotalBlocksInGame(totalBlocksInGame);
        report.setTotalBlocksInShader(totalBlocksInShader);
        report.setTagSupportEnabled(config.isTagSupportEnabled());

        EuphoriaCompanion.LOGGER.info("Analysis complete for {}", shaderpackName);
        return report;
    }

    /**
     * Parse block.properties file with conditional directives
     */
    private BlockPropertiesParser parseBlockProperties(Path shaderpackPath) throws IOException {
        Path propertiesFile = null;
        Path tempFile = null;

        // Check if it's a directory or ZIP file
        if (Files.isDirectory(shaderpackPath)) {
            propertiesFile = shaderpackPath.resolve("shaders/block.properties");
            if (!Files.exists(propertiesFile)) {
                return null;
            }
        } else if (shaderpackPath.toString().toLowerCase().endsWith(".zip")) {
            // Validate ZIP file before opening
            if (!isValidZipFile(shaderpackPath)) {
                EuphoriaCompanion.LOGGER.warn("File is not a valid ZIP: {}", shaderpackPath.getFileName());
                return null;
            }

            try (FileSystem zipFs = FileSystems.newFileSystem(shaderpackPath, (ClassLoader) null)) {
                Path zipPropertiesFile = zipFs.getPath("/shaders/block.properties");
                if (!Files.exists(zipPropertiesFile)) {
                    return null;
                }

                // Copy to temp file for parsing
                tempFile = Files.createTempFile("block", ".properties");
                try {
                    Files.copy(zipPropertiesFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    propertiesFile = tempFile;
                } catch (IOException e) {
                    // Clean up temp file if copy fails
                    Files.deleteIfExists(tempFile);
                    throw e;
                }
            } catch (Exception e) {
                // Clean up temp file if ZIP reading fails
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {}
                }
                throw e;
            }
        }

        if (propertiesFile == null) {
            return null;
        }

        try {
            BlockPropertiesParser parser = new BlockPropertiesParser(config, currentMCVersion);
            parser.parse(propertiesFile);

            // Clean up temp file if created
            if (tempFile != null) {
                Files.deleteIfExists(propertiesFile);
            }

            return parser;
        } catch (Exception e) {
            // Clean up temp file on parsing failure
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    /**
     * Tag Resolution - resolves tag identifiers to actual blocks
     * Excludes blocks that are already directly defined in block.properties
     * Implements first-assignment-wins: blocks claimed by earlier tags won't appear in later tags
     * Only processes tags that are actually assigned to block.xxxxx properties
     */
    private Map<String, Set<String>> resolveTagsToBlocks(BlockPropertiesParser parser) {
        Map<String, Set<String>> tagToBlocks = new LinkedHashMap<>();  // Preserve order

        if (!config.isTagSupportEnabled()) {
            return tagToBlocks;
        }

        Map<String, String> tagDefinitions = parser.getTagDefinitions();
        Map<String, Integer> blockToProperty = parser.getBlockToProperty();
        Map<String, Integer> tagToProperty = parser.getTagToProperty();

        // Build set of directly defined blocks (extract base block IDs from blockstate strings)
        Set<String> directlyDefinedBlocks = new HashSet<>();
        for (String fullId : blockToProperty.keySet()) {
            BlockStateValidator.BlockStateSpec spec = BlockStateValidator.parseBlockState(fullId);
            if (spec != null) {
                // Has blockstates - add just the block ID
                directlyDefinedBlocks.add(spec.blockId());
            } else {
                // No blockstates - add as is
                directlyDefinedBlocks.add(fullId);
            }
        }

        // Track blocks already claimed by tags (first-assignment-wins)
        Set<String> alreadyClaimedBlocks = new HashSet<>(directlyDefinedBlocks);

        // Process tags in order of assignment (tagToProperty preserves insertion order from parsing)
        // Only process tags that are actually assigned to block.XX properties
        for (Map.Entry<String, Integer> entry : tagToProperty.entrySet()) {
            String identifier = entry.getKey();

            // Get the tag definition (value like "%oak_logs" or "%corals %coral_plants")
            String value = tagDefinitions.get(identifier);
            if (value == null) {
                EuphoriaCompanion.LOGGER.warn("Tag {} assigned to property but not defined", identifier);
                continue;
            }

            // Value can be one or more tag references like "%oak_logs" or "%corals %coral_plants %wall_corals"
            Set<String> blocks = resolveTagReferences(value);

            // Remove blocks that are already claimed (by direct definitions or earlier tags)
            blocks.removeAll(alreadyClaimedBlocks);

            // Only include tag if it still has blocks not covered by earlier definitions
            if (!blocks.isEmpty()) {
                tagToBlocks.put(identifier, blocks);

                // Mark these blocks as claimed for future tags
                alreadyClaimedBlocks.addAll(blocks);

                EuphoriaCompanion.LOGGER.debug("Resolved tag {} ({}) to {} blocks (after filtering already claimed)",
                        identifier, value, blocks.size());
            } else {
                EuphoriaCompanion.LOGGER.debug("Tag {} ({}) fully covered by earlier definitions, skipping",
                        identifier, value);
            }
        }

        return tagToBlocks;
    }

    /**
     * Resolves one or more tag references (space-separated) to block IDs
     * Example: "%oak_logs" or "%corals %coral_plants %wall_corals"
     */
    private Set<String> resolveTagReferences(String tagReferences) {
        Set<String> allBlocks = new HashSet<>();

        // Split by whitespace to handle multiple tag references
        String[] refs = tagReferences.trim().split("\\s+");

        for (String ref : refs) {
            ref = ref.trim();
            if (ref.isEmpty()) {
                continue;
            }

            // Each reference should start with '%'
            if (!ref.startsWith("%")) {
                EuphoriaCompanion.LOGGER.warn("Tag reference doesn't start with %: {}", ref);
                continue;
            }

            // Strip the '%' prefix
            String tagName = ref.substring(1);

            // Add minecraft: namespace if not present
            if (!tagName.contains(":")) {
                tagName = "minecraft:" + tagName;
            }

            // Resolve this tag to blocks
            Set<String> blocks = resolveSingleTag(tagName);
            allBlocks.addAll(blocks);
        }

        return allBlocks;
    }

    /**
     * Resolves a single tag name to a set of block IDs
     * Tag name should be in format "namespace:tagname" (e.g., "minecraft:oak_logs")
     */
    private Set<String> resolveSingleTag(String tagName) {
        Set<String> blocks = new HashSet<>();

        try {
            // Parse tag name (e.g., "minecraft:leaves" or "c:ores")
            String[] parts = tagName.split(":", 2);
            if (parts.length != 2) {
                EuphoriaCompanion.LOGGER.warn("Invalid tag name format: {}", tagName);
                return blocks;
            }

            Identifier tagId = Identifier.of(parts[0], parts[1]);
            TagKey<Block> tagKey = TagKey.of(Registries.BLOCK.getKey(), tagId);

            // Get all blocks with this tag
            Registries.BLOCK.iterateEntries(tagKey).forEach(entry -> {
                Block block = entry.value();
                Identifier blockId = Registries.BLOCK.getId(block);
                blocks.add(blockId.toString());
            });

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to resolve tag: {}", tagName, e);
        }

        return blocks;
    }

    /**
     * Categorize Missing Blocks
     */
    private Map<String, Map<String, List<String>>> categorizeMissingBlocks(
            BlockPropertiesParser parser,
            Map<String, Set<String>> tagToBlocks) {

        EuphoriaCompanion.LOGGER.info("Categorizing blocks using {} scan mode",
            config.scanMode == ModConfig.ScanMode.DEEP ? "DEEP" : "QUICK");

        Map<String, Integer> blockToProperty = parser.getBlockToProperty();
        Map<String, Integer> tagToProperty = parser.getTagToProperty();

        // Build set of all covered blocks (extract block IDs from blockstate strings)
        Set<String> coveredBlocks = new HashSet<>();
        for (String fullId : blockToProperty.keySet()) {
            BlockStateValidator.BlockStateSpec spec = BlockStateValidator.parseBlockState(fullId);
            if (spec != null) {
                // Has blockstates - add just the block ID
                coveredBlocks.add(spec.blockId());
            } else {
                // No blockstates - add as is
                coveredBlocks.add(fullId);
            }
        }

        // Add blocks covered by tags
        for (Map.Entry<String, Integer> entry : tagToProperty.entrySet()) {
            String tagIdentifier = entry.getKey();
            if (tagToBlocks.containsKey(tagIdentifier)) {
                coveredBlocks.addAll(tagToBlocks.get(tagIdentifier));
            }
        }

        // Categorize all blocks from registry
        Map<String, Map<String, List<String>>> missingByMod = new TreeMap<>();

        for (Block block : Registries.BLOCK) {
            Identifier blockId = Registries.BLOCK.getId(block);
            String blockIdStr = blockId.toString();

            // Skip if already covered (by direct definitions or used tags)
            if (coveredBlocks.contains(blockIdStr)) {
                continue;
            }

            // Categorize based on block properties
            String category = categorizeBlock(block);
            if (category != null) {
                String namespace = blockId.getNamespace();
                missingByMod.computeIfAbsent(namespace, k -> new TreeMap<>())
                        .computeIfAbsent(category, k -> new ArrayList<>())
                        .add(blockIdStr);
            }
        }

        return missingByMod;
    }

    /**
     * Categorizes a block based on config-enabled categories
     */
    private String categorizeBlock(Block block) {
        if (config.scanMode == ModConfig.ScanMode.DEEP) {
            return categorizeBlockDeep(block);
        } else {
            return categorizeBlockQuick(block);
        }
    }

    /**
     * Quick scan - only checks the default blockstate
     */
    private String categorizeBlockQuick(Block block) {
        BlockState defaultState = block.getDefaultState();

        // Check categories in priority order based on config
        // Block Entity is the highest priority since it may require special shader handling
        if (config.checkBlockEntity && block instanceof net.minecraft.block.BlockEntityProvider) {
            return "Block Entity";
        } else if (config.checkLightEmitting && defaultState.getLuminance() > 0) {
            return "Light Emitting";
        } else if (config.checkTranslucent && isTranslucent(defaultState)) {
            return "Translucent";
        } else if (config.checkNonFull && isNonFull(defaultState)) {
            return "Non-Full";
        } else if (config.checkFull) {
            return "Full";
        }

        return null; // Skip if category is disabled
    }

    /**
     * Deep scan - checks ALL possible blockstates
     * Catches cases like redstone lamps that only emit light when lit=true
     */
    private String categorizeBlockDeep(Block block) {
        // Block Entity check first (same as quick scan - highest priority)
        if (config.checkBlockEntity && block instanceof net.minecraft.block.BlockEntityProvider) {
            return "Block Entity";
        }

        boolean anyLightEmitting = false;
        boolean anyTranslucent = false;
        boolean anyNonFull = false;
        boolean allFull = true;

        // Iterate through all possible blockstates
        for (BlockState state : block.getStateManager().getStates()) {
            if (config.checkLightEmitting && state.getLuminance() > 0) {
                anyLightEmitting = true;
            }
            if (config.checkTranslucent && isTranslucent(state)) {
                anyTranslucent = true;
            }
            if (config.checkNonFull && isNonFull(state)) {
                anyNonFull = true;
            }
            if (!state.isOpaqueFullCube()) {
                allFull = false;
            }
        }

        // Return the highest priority category that matches (same priority order as quick scan)
        if (config.checkLightEmitting && anyLightEmitting) {
            return "Light Emitting";
        } else if (config.checkTranslucent && anyTranslucent) {
            return "Translucent";
        } else if (config.checkNonFull && anyNonFull) {
            return "Non-Full";
        } else if (config.checkFull && allFull) {
            return "Full";
        }

        return null; // Skip if category is disabled
    }

    /**
     * Checks if a block is translucent (uses translucent render layer)
     */
    private boolean isTranslucent(BlockState state) {
        try {
            BlockRenderLayer renderLayer = BlockRenderLayers.getBlockLayer(state);
            // Both TRANSLUCENT and TRIPWIRE are treated as translucent by shaders
            return renderLayer == BlockRenderLayer.TRANSLUCENT || renderLayer == BlockRenderLayer.TRIPWIRE;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a block is non-full (allows light to leak on any side)
     */
    private boolean isNonFull(BlockState state) {
        try {
            // Check if light can leak through any side
            return !state.isOpaqueFullCube();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate Render Layers
     */
    private Map<String, RenderLayerMismatch> validateRenderLayers(BlockPropertiesParser parser) {
        Map<String, RenderLayerMismatch> mismatches = new HashMap<>();

        if (!config.validateRenderLayers) {
            return mismatches;
        }

        Map<String, String> blockToRenderLayer = parser.getBlockToRenderLayer();

        for (Map.Entry<String, String> entry : blockToRenderLayer.entrySet()) {
            String blockId = entry.getKey();
            String expectedLayer = entry.getValue();

            // Get actual render layer from block
            String actualLayer = getActualRenderLayer(blockId);

            if (actualLayer != null && !expectedLayer.equalsIgnoreCase(actualLayer)) {
                mismatches.put(blockId, new RenderLayerMismatch(expectedLayer, actualLayer));
            }
        }

        return mismatches;
    }

    /**
     * Gets the actual render layer of a block
     */
    private String getActualRenderLayer(String blockId) {
        try {
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) {
                return null;
            }

            // Check if the block is actually registered
            if (!Registries.BLOCK.containsId(id)) {
                return null; // Block doesn't exist in registry
            }

            Block block = Registries.BLOCK.get(id);

            BlockState state = block.getDefaultState();
            if (state == null) {
                EuphoriaCompanion.LOGGER.warn("Block has no default state: {}", id);
                return null;
            }

            BlockRenderLayer renderLayer = BlockRenderLayers.getBlockLayer(state);

            // Map BlockRenderLayer enum to shader layer names
            // Note: In 1.21.11+, CUTOUT_MIPPED was merged into CUTOUT
            return switch (renderLayer) {
                case SOLID -> "solid";
                case CUTOUT -> "cutout";
                case TRANSLUCENT -> "translucent";
                case TRIPWIRE -> "translucent"; // Shaders treat tripwire as translucent
            };

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to get render layer for: {}", blockId, e);
            return null;
        }
    }

    /**
     * Calculates total number of blocks registered in the game
     */
    private int calculateTotalBlocksInGame() {
        return Registries.BLOCK.size();
    }

    /**
     * Calculates total number of unique blocks covered by shader definitions
     * Only counts blocks that are actually used (from direct definitions or tags assigned to properties)
     */
    private int calculateTotalBlocksInShader(Map<String, Integer> blockToProperty, Map<String, Set<String>> tagToBlocks, Map<String, Integer> tagToProperty) {
        Set<String> coveredBlocks = new HashSet<>();

        // Add blocks from direct definitions (extract base block IDs from blockstate strings)
        for (String fullId : blockToProperty.keySet()) {
            BlockStateValidator.BlockStateSpec spec = BlockStateValidator.parseBlockState(fullId);
            if (spec != null) {
                // Has blockstates - add just the block ID
                coveredBlocks.add(spec.blockId());
            } else {
                // No blockstates - add as is
                coveredBlocks.add(fullId);
            }
        }

        // Only add blocks from tags that are actually assigned to a block.XX property
        for (Map.Entry<String, Integer> entry : tagToProperty.entrySet()) {
            String tagIdentifier = entry.getKey();
            if (tagToBlocks.containsKey(tagIdentifier)) {
                coveredBlocks.addAll(tagToBlocks.get(tagIdentifier));
            }
        }

        return coveredBlocks.size();
    }

    /**
     * Validates that a file is a valid ZIP archive
     */
    private boolean isValidZipFile(Path path) {
        try {
            // Try to open as ZIP and immediately close
            try (FileSystem ignored = FileSystems.newFileSystem(path, (ClassLoader) null)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Represents a render layer mismatch
     */
    public record RenderLayerMismatch(String expected, String actual) {
    }
}
