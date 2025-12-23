package eclipse.euphoriacompanion.analyzer;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.config.ModConfig;
import eclipse.euphoriacompanion.parser.BlockPropertiesParser;
import eclipse.euphoriacompanion.report.AnalysisReport;
import net.minecraft.block.Block;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Main analyzer that orchestrates all phases of shader compatibility analysis.
 * Adapted for Minecraft 1.7.10 with metadata system instead of BlockStates.
 */
public class ShaderAnalyzer {
    private final ModConfig config;
    private final int currentMCVersion;

    public ShaderAnalyzer(ModConfig config, int currentMCVersion) {
        this.config = config;
        this.currentMCVersion = currentMCVersion;
    }

    /**
     * Converts categorized metadata analysis results into a report-friendly format.
     *
     * @param incompleteMetadata Categorized metadata from BlockStateValidator
     * @return Report-friendly map: blockId -> category -> metadata values
     */
    private static Map<String, Map<String, List<String>>> convertCategorizedMetadataToReport(Map<String, BlockStateValidator.CategorizedMetadata> incompleteMetadata) {
        Map<String, Map<String, List<String>>> incompleteBlockStatesConverted = new HashMap<>();
        for (Map.Entry<String, BlockStateValidator.CategorizedMetadata> entry : incompleteMetadata.entrySet()) {
            BlockStateValidator.CategorizedMetadata categorized = entry.getValue();
            Map<String, List<String>> categoryMap = new HashMap<>();

            if (!categorized.getVisuallyDistinct().isEmpty()) {
                List<String> visuallyDistinctStrings = new ArrayList<>();
                for (Integer meta : categorized.getVisuallyDistinct()) {
                    visuallyDistinctStrings.add(String.valueOf(meta));
                }
                categoryMap.put("Visually Distinct (new textures not in your definitions)", visuallyDistinctStrings);
            }

            if (!categorized.getDuplicatesOfDefined().isEmpty()) {
                for (Map.Entry<Integer, List<Integer>> dupEntry : categorized.getDuplicatesOfDefined().entrySet()) {
                    Integer definedMeta = dupEntry.getKey();
                    List<Integer> duplicates = dupEntry.getValue();

                    List<String> duplicateStrings = new ArrayList<>();
                    for (Integer meta : duplicates) {
                        duplicateStrings.add(String.valueOf(meta));
                    }

                    String category = "Same texture as meta " + definedMeta;
                    categoryMap.put(category, duplicateStrings);
                }
            }

            if (!categorized.getPotentiallyDistinct().isEmpty()) {
                List<String> potentiallyDistinctStrings = new ArrayList<>();
                for (Integer meta : categorized.getPotentiallyDistinct()) {
                    potentiallyDistinctStrings.add(String.valueOf(meta));
                }
                categoryMap.put("Potentially Distinct (WARNING: Test in a separate save, may brick your world!)", potentiallyDistinctStrings);
            }

            incompleteBlockStatesConverted.put(entry.getKey(), categoryMap);
        }
        return incompleteBlockStatesConverted;
    }

    public AnalysisReport analyze(Path shaderpackPath) throws IOException {
        String shaderpackName = shaderpackPath.getFileName().toString();
        EuphoriaCompanion.LOGGER.info("Processing: {}", shaderpackName);

        BlockPropertiesParser parser = parseBlockProperties(shaderpackPath);
        if (parser == null) {
            EuphoriaCompanion.LOGGER.warn("No block.properties found in {}", shaderpackName);
            return new AnalysisReport(shaderpackName);
        }

        Map<String, Map<String, List<String>>> missingBlocksByMod = categorizeMissingBlocks(parser);

        Map<String, BlockStateValidator.CategorizedMetadata> incompleteMetadata =
                BlockStateValidator.validateBlockMetadata(parser.getBlockToProperty());

        Map<String, Map<String, List<String>>> incompleteBlockStatesConverted = convertCategorizedMetadataToReport(incompleteMetadata);

        Map<String, List<Integer>> duplicateDefinitions = parser.getDuplicateBlocks();

        int totalBlocksInGame = calculateTotalBlocksInGame();
        int totalBlocksInShader = calculateTotalBlocksInShader(parser.getBlockToProperty());

        AnalysisReport report = new AnalysisReport(shaderpackName);
        report.setMissingBlocksByMod(missingBlocksByMod);
        report.setIncompleteBlockStates(incompleteBlockStatesConverted);
        report.setDuplicateDefinitions(duplicateDefinitions);
        report.setTotalBlocksInGame(totalBlocksInGame);
        report.setTotalBlocksInShader(totalBlocksInShader);

        EuphoriaCompanion.LOGGER.info("Analysis complete for {}", shaderpackName);
        return report;
    }

    /**
     * Parse block.properties file with conditional directives
     */
    private BlockPropertiesParser parseBlockProperties(Path shaderpackPath) throws IOException {
        Path propertiesFile = null;
        Path tempFile = null;

        if (Files.isDirectory(shaderpackPath)) {
            propertiesFile = shaderpackPath.resolve("shaders/block.properties");
            if (!Files.exists(propertiesFile)) {
                return null;
            }
        } else if (shaderpackPath.toString().toLowerCase().endsWith(".zip")) {
            if (!isValidZipFile(shaderpackPath)) {
                EuphoriaCompanion.LOGGER.warn("File is not a valid ZIP: {}", shaderpackPath.getFileName());
                return null;
            }

            try {
                try (FileSystem zipFs = FileSystems.newFileSystem(shaderpackPath, null)) {
                    Path zipPropertiesFile = zipFs.getPath("/shaders/block.properties");
                    if (!Files.exists(zipPropertiesFile)) {
                        return null;
                    }

                    tempFile = Files.createTempFile("block", ".properties");
                    try {
                        Files.copy(zipPropertiesFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        propertiesFile = tempFile;
                    } catch (IOException e) {
                        Files.deleteIfExists(tempFile);
                        throw e;
                    }
                }
            } catch (Exception e) {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                    }
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

            if (tempFile != null) {
                Files.deleteIfExists(propertiesFile);
            }

            return parser;
        } catch (Exception e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
            throw e;
        }
    }

    /**
     * Categorize Missing Blocks
     */
    private Map<String, Map<String, List<String>>> categorizeMissingBlocks(BlockPropertiesParser parser) {

        EuphoriaCompanion.LOGGER.info("Categorizing blocks using {} scan mode", config.scanMode == ModConfig.ScanMode.DEEP ? "DEEP" : "QUICK");

        Map<String, Integer> blockToProperty = parser.getBlockToProperty();

        Set<String> coveredBlocks = new HashSet<>();
        for (String fullId : blockToProperty.keySet()) {
            BlockStateValidator.BlockMetadataSpec spec = BlockStateValidator.parseBlockMetadata(fullId);
            if (spec != null) {
                coveredBlocks.add(spec.getBlockId());
            } else {
                coveredBlocks.add(fullId);
            }
        }

        Map<String, Map<String, List<String>>> missingByMod = new TreeMap<>();

        for (Object o : Block.blockRegistry) {
            Block block = (Block) o;
            if (block == null) {
                continue;
            }

            String blockIdStr = Block.blockRegistry.getNameForObject(block);
            if (blockIdStr == null || blockIdStr.isEmpty()) {
                continue;
            }

            if (coveredBlocks.contains(blockIdStr)) {
                continue;
            }

            String category = categorizeBlock(block);
            if (category != null) {
                String namespace = "minecraft";
                if (blockIdStr.contains(":")) {
                    namespace = blockIdStr.split(":", 2)[0];
                }

                Map<String, List<String>> categoriesForMod = missingByMod.computeIfAbsent(namespace, k -> new TreeMap<>());

                List<String> blocksInCategory = categoriesForMod.computeIfAbsent(category, k -> new ArrayList<>());

                blocksInCategory.add(blockIdStr);
            }
        }

        EuphoriaCompanion.LOGGER.info("Block categorization complete");
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
     * Quick scan - only checks the default state (metadata 0)
     */
    private String categorizeBlockQuick(Block block) {
        // Block Entity has highest priority since it may require special shader handling
        if (config.checkBlockEntity && block.hasTileEntity(0)) {
            return "Block Entity";
        } else if (config.checkLightEmitting && block.getLightValue() > 0) {
            return "Light Emitting";
        } else if (config.checkTranslucent && isTranslucent(block)) {
            return "Translucent";
        } else if (config.checkNonFull && !block.isOpaqueCube()) {
            return "Non-Full";
        } else if (config.checkFull && block.isOpaqueCube()) {
            return "Full";
        }

        return null;
    }

    /**
     * Deep scan - checks ALL possible metadata values (0-15)
     * Catches cases like redstone lamps that only emit light when powered
     */
    private String categorizeBlockDeep(Block block) {
        // Block Entity has the highest priority since it may require special shader handling
        boolean anyHasTileEntity = false;
        for (int meta = 0; meta < 16; meta++) {
            if (block.hasTileEntity(meta)) {
                anyHasTileEntity = true;
                break;
            }
        }

        if (config.checkBlockEntity && anyHasTileEntity) {
            return "Block Entity";
        }

        // 1.7.10 doesn't support per-metadata light/opacity checks, so fall back to default state
        if (config.checkLightEmitting && block.getLightValue() > 0) {
            return "Light Emitting";
        } else if (config.checkTranslucent && isTranslucent(block)) {
            return "Translucent";
        } else if (config.checkNonFull && !block.isOpaqueCube()) {
            return "Non-Full";
        } else if (config.checkFull && block.isOpaqueCube()) {
            return "Full";
        }

        return null;
    }

    /**
     * In 1.7.10: pass 0 = solid, pass 1 = translucent
     */
    private boolean isTranslucent(Block block) {
        try {
            return block.getRenderBlockPass() == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private int calculateTotalBlocksInGame() {
        int count = 0;
        for (Object ignored : Block.blockRegistry) {
            count++;
        }
        return count;
    }

    private int calculateTotalBlocksInShader(Map<String, Integer> blockToProperty) {
        Set<String> coveredBlocks = new HashSet<>();

        for (String fullId : blockToProperty.keySet()) {
            BlockStateValidator.BlockMetadataSpec spec = BlockStateValidator.parseBlockMetadata(fullId);
            if (spec != null) {
                coveredBlocks.add(spec.getBlockId());
            } else {
                coveredBlocks.add(fullId);
            }
        }

        return coveredBlocks.size();
    }

    /**
     * Validates that a file is a valid ZIP archive
     */
    private boolean isValidZipFile(Path path) {
        try {
            try (FileSystem ignored = FileSystems.newFileSystem(path, null)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

}