package eclipse.euphoriacompanion.report;

import eclipse.euphoriacompanion.util.BlockIdFormatter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates formatted analysis reports.
 */
public class ReportGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Comparator for sorting metadata categories in priority order.
     * Priority: Visually Distinct > Same texture as meta X (numeric) > Potentially Distinct
     */
    private static final Comparator<Map.Entry<String, List<String>>> METADATA_CATEGORY_COMPARATOR = (e1, e2) -> {
        String cat1 = e1.getKey();
        String cat2 = e2.getKey();

        if (cat1.startsWith("Visually Distinct")) return -1;
        if (cat2.startsWith("Visually Distinct")) return 1;

        if (cat1.startsWith("Potentially Distinct")) return 1;
        if (cat2.startsWith("Potentially Distinct")) return -1;

        if (cat1.startsWith("Same texture") && cat2.startsWith("Same texture")) {
            try {
                int meta1 = Integer.parseInt(cat1.replaceAll(".*meta (\\d+).*", "$1"));
                int meta2 = Integer.parseInt(cat2.replaceAll(".*meta (\\d+).*", "$1"));
                return Integer.compare(meta1, meta2);
            } catch (NumberFormatException e) {
                return cat1.compareTo(cat2);
            }
        }

        return cat1.compareTo(cat2);
    };

    /**
     * Generates and saves a report to the specified path.
     */
    public static void generateReport(AnalysisReport report, Path outputPath, boolean quoteBlockIds) throws IOException {
        if (outputPath.getParent() == null) {
            throw new IOException("Output path has no parent directory: " + outputPath);
        }

        Files.createDirectories(outputPath.getParent());

        Path tempPath = outputPath.getParent().resolve(outputPath.getFileName() + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            writeHeader(writer, report);
            writeMissingBlocks(writer, report, quoteBlockIds);
            writeIncompleteBlockStates(writer, report, quoteBlockIds);
            writeDuplicateDefinitions(writer, report, quoteBlockIds);
        }

        // Atomic rename - only appears as complete file
        Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Writes the report header
     */
    private static void writeHeader(BufferedWriter writer, AnalysisReport report) throws IOException {
        writer.write("=== SHADER ANALYSIS: " + report.getShaderpackName() + " ===\n");
        writer.write("Generated: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n\n");

        int totalInGame = report.getTotalBlocksInGame();
        int totalInShader = report.getTotalBlocksInShader();
        int totalMissing = report.getTotalMissingBlocks();

        writer.write("STATISTICS:\n");
        writer.write("  Total blocks in game: " + totalInGame + "\n");
        writer.write("  Blocks defined in shader: " + totalInShader + "\n");
        writer.write("  Missing blocks: " + totalMissing + "\n");

        double coverage = totalInGame > 0 ? 100.0 * (1.0 - (double) totalMissing / totalInGame) : 0.0;
        writer.write(String.format("  Coverage: %.2f%%\n\n", coverage));
    }

    /**
     * Writes the missing blocks section
     */
    private static void writeMissingBlocks(BufferedWriter writer, AnalysisReport report, boolean quoteBlockIds) throws IOException {
        writer.write("----------------------------------------\n");
        writer.write("MISSING BLOCKS BY MOD:\n\n");

        Map<String, Map<String, List<String>>> missingByMod = report.getMissingBlocksByMod();

        if (missingByMod.isEmpty()) {
            writer.write("No missing blocks found.\n\n");
            return;
        }

        for (Map.Entry<String, Map<String, List<String>>> modEntry : missingByMod.entrySet()) {
            String modName = modEntry.getKey();
            Map<String, List<String>> categories = modEntry.getValue();

            int totalBlocks = 0;
            for (List<String> blocks : categories.values()) {
                totalBlocks += blocks.size();
            }

            writer.write(modName + " (" + totalBlocks + " blocks):\n");

            for (Map.Entry<String, List<String>> categoryEntry : categories.entrySet()) {
                String category = categoryEntry.getKey();
                List<String> blocks = categoryEntry.getValue();

                writer.write("  " + category + " (" + blocks.size() + "):\n");

                Collections.sort(blocks);

                for (String block : blocks) {
                    writer.write(" " + BlockIdFormatter.formatId(block, quoteBlockIds) + "\n");
                }

                writer.write("\n");
            }
        }
    }

    /**
     * Writes the missing meta values section
     */
    private static void writeIncompleteBlockStates(BufferedWriter writer, AnalysisReport report, boolean quoteBlockIds)
            throws IOException {
        Map<String, Map<String, List<String>>> incompleteBlockStates = report.getIncompleteBlockStates();

        writer.write("----------------------------------------\n");
        writer.write("MISSING META VALUES:\n\n");
        writer.write("WARNING: Always test missing metadata in a separate save first.\n");
        writer.write("Some invalid metadata values can crash the game or brick your world!\n\n");

        if (incompleteBlockStates.isEmpty()) {
            writer.write("All metadata values are complete.\n\n");
            return;
        }

        List<Map.Entry<String, Map<String, List<String>>>> sortedBlocks =
                new ArrayList<>(incompleteBlockStates.entrySet());
        sortedBlocks.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, Map<String, List<String>>> blockEntry : sortedBlocks) {
            String blockId = blockEntry.getKey();
            Map<String, List<String>> categoriesMap = blockEntry.getValue();

            writer.write(BlockIdFormatter.formatId(blockId, quoteBlockIds) + ":\n");

            // Sort categories for better readability
            List<Map.Entry<String, List<String>>> sortedCategories = sortMetadataCategories(categoriesMap);

            for (Map.Entry<String, List<String>> categoryEntry : sortedCategories) {
                String categoryName = categoryEntry.getKey();
                List<String> missingValues = categoryEntry.getValue();

                writer.write("  " + categoryName + " - Missing values: " +
                        String.join(",", missingValues) + "\n");
            }

            writer.write("\n");
        }
    }

    /**
     * Sorts metadata categories in priority order for report display.
     * Uses METADATA_CATEGORY_COMPARATOR for consistent sorting.
     */
    private static List<Map.Entry<String, List<String>>> sortMetadataCategories(Map<String, List<String>> categoriesMap) {
        List<Map.Entry<String, List<String>>> sortedCategories = new ArrayList<>(categoriesMap.entrySet());
        sortedCategories.sort(METADATA_CATEGORY_COMPARATOR);
        return sortedCategories;
    }

    /**
     * Writes the duplicate definitions section
     */
    private static void writeDuplicateDefinitions(BufferedWriter writer, AnalysisReport report, boolean quoteBlockIds)
            throws IOException {
        Map<String, List<Integer>> duplicates = report.getDuplicateDefinitions();

        writer.write("----------------------------------------\n");
        writer.write("DUPLICATE DEFINITIONS:\n\n");

        if (duplicates.isEmpty()) {
            writer.write("No duplicate definitions found.\n\n");
            return;
        }

        List<Map.Entry<String, List<Integer>>> sortedDuplicates =
                new ArrayList<>(duplicates.entrySet());
        sortedDuplicates.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, List<Integer>> entry : sortedDuplicates) {
            String blockState = entry.getKey();
            List<Integer> propertyIds = entry.getValue();

            Collections.sort(propertyIds);

            StringBuilder propertyIdsStr = new StringBuilder();
            for (int i = 0; i < propertyIds.size(); i++) {
                if (i > 0) {
                    propertyIdsStr.append(", ");
                }
                propertyIdsStr.append("block.").append(propertyIds.get(i));
            }

            writer.write(BlockIdFormatter.formatId(blockState, quoteBlockIds) + " is defined multiple times:\n");
            writer.write("  Properties: " + propertyIdsStr + "\n\n");
        }
    }
}
