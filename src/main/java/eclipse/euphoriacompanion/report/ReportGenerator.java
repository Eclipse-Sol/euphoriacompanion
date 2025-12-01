package eclipse.euphoriacompanion.report;

import eclipse.euphoriacompanion.analyzer.ShaderAnalyzer.RenderLayerMismatch;

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
     * Generates and saves a report to the specified path.
     */
    public static void generateReport(AnalysisReport report, Path outputPath) throws IOException {
        if (outputPath.getParent() == null) {
            throw new IOException("Output path has no parent directory: " + outputPath);
        }

        Files.createDirectories(outputPath.getParent());

        // Write to temporary file first
        Path tempPath = outputPath.getParent().resolve(outputPath.getFileName() + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            writeHeader(writer, report);
            writeMissingBlocks(writer, report);
            writeTagCoverage(writer, report);
            writeUnusedTags(writer, report);
            writeIncompleteBlockStates(writer, report);
            writeDuplicateDefinitions(writer, report);
            writeRenderLayerMismatches(writer, report);
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

        // Statistics
        int totalInGame = report.getTotalBlocksInGame();
        int totalInShader = report.getTotalBlocksInShader();
        int totalMissing = report.getTotalMissingBlocks();

        writer.write("STATISTICS:\n");
        writer.write("  Total blocks in game: " + totalInGame + "\n");
        writer.write("  Blocks defined in shader: " + totalInShader + "\n");
        writer.write("  Missing blocks: " + totalMissing + "\n");

        // Calculate coverage percentage: 100 * (1 - missing/total)
        double coverage = totalInGame > 0 ? 100.0 * (1.0 - (double) totalMissing / totalInGame) : 0.0;
        writer.write(String.format("  Coverage: %.2f%%\n\n", coverage));
    }

    /**
     * Writes the missing blocks section
     */
    private static void writeMissingBlocks(BufferedWriter writer, AnalysisReport report) throws IOException {
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

            // Count total blocks for this mod
            int totalBlocks = categories.values().stream()
                .mapToInt(List::size)
                .sum();

            writer.write(modName + " (" + totalBlocks + " blocks):\n");

            // Write each category
            for (Map.Entry<String, List<String>> categoryEntry : categories.entrySet()) {
                String category = categoryEntry.getKey();
                List<String> blocks = categoryEntry.getValue();

                writer.write("  " + category + " (" + blocks.size() + "):\n");

                // Sort blocks alphabetically
                Collections.sort(blocks);

                // Write blocks (newline-separated only, no decorative characters)
                for (String block : blocks) {
                    writer.write(" " + block + "\n");
                }

                writer.write("\n");
            }
        }
    }

    /**
     * Writes the tag coverage section
     */
    private static void writeTagCoverage(BufferedWriter writer, AnalysisReport report) throws IOException {
        writer.write("----------------------------------------\n");
        writer.write("COVERED BY TAGS:\n\n");

        Map<String, Set<String>> tagCoverage = report.getTagCoverage();
        Map<String, String> tagDefinitions = report.getTagDefinitions();
        Map<String, Integer> tagToProperty = report.getTagToProperty();

        if (tagCoverage.isEmpty()) {
            if (report.isTagSupportEnabled()) {
                writer.write("No tag coverage.\n\n");
            } else {
                writer.write("Tag support is disabled.\n\n");
            }
            return;
        }

        for (Map.Entry<String, Set<String>> entry : tagCoverage.entrySet()) {
            String tagIdentifier = entry.getKey();
            Set<String> blocks = entry.getValue();

            String tagValue = tagDefinitions.getOrDefault(tagIdentifier, "unknown");
            Integer propertyId = tagToProperty.get(tagIdentifier);
            String propertyStr = propertyId != null ? "block." + propertyId : "unused";

            writer.write("Tag: " + tagIdentifier + " = " + tagValue + " (" + propertyStr + ") - " + blocks.size() + " blocks\n");

            // Sort blocks alphabetically
            List<String> sortedBlocks = new ArrayList<>(blocks);
            Collections.sort(sortedBlocks);

            // Write blocks (newline-separated only)
            for (String block : sortedBlocks) {
                writer.write(block + "\n");
            }

            writer.write("\n");
        }
    }

    /**
     * Writes the unused tags warning section
     */
    private static void writeUnusedTags(BufferedWriter writer, AnalysisReport report) throws IOException {
        Map<String, String> tagDefinitions = report.getTagDefinitions();
        Map<String, Integer> tagToProperty = report.getTagToProperty();

        // Find tags that are defined but not assigned to any property
        List<String> unusedTags = new ArrayList<>();
        for (String tagIdentifier : tagDefinitions.keySet()) {
            if (!tagToProperty.containsKey(tagIdentifier)) {
                unusedTags.add(tagIdentifier);
            }
        }

        if (unusedTags.isEmpty()) {
            return; // No unused tags, skip this section entirely
        }

        writer.write("----------------------------------------\n");
        writer.write("UNUSED TAG DEFINITIONS:\n\n");
        writer.write("WARNING: The following tags are defined but not assigned to any block.XX property.\n");
        writer.write("These tags will not affect shader behavior.\n\n");

        Collections.sort(unusedTags);

        for (String tagIdentifier : unusedTags) {
            String tagValue = tagDefinitions.get(tagIdentifier);
            writer.write("  " + tagIdentifier + " = " + tagValue + "\n");
        }

        writer.write("\n");
    }

    /**
     * Writes the incomplete blockstate section
     */
    private static void writeIncompleteBlockStates(BufferedWriter writer, AnalysisReport report)
            throws IOException {
        Map<String, Map<String, List<String>>> incompleteBlockStates = report.getIncompleteBlockStates();

        writer.write("----------------------------------------\n");
        writer.write("INCOMPLETE BLOCKSTATE DEFINITIONS:\n\n");

        if (incompleteBlockStates.isEmpty()) {
            writer.write("All blockstate definitions are complete.\n\n");
            return;
        }

        // Sort by block ID
        List<Map.Entry<String, Map<String, List<String>>>> sortedBlocks =
            new ArrayList<>(incompleteBlockStates.entrySet());
        sortedBlocks.sort(Map.Entry.comparingByKey());

        for (var blockEntry : sortedBlocks) {
            String blockId = blockEntry.getKey();
            Map<String, List<String>> missingByProperty = blockEntry.getValue();

            writer.write(blockId + ":\n");

            for (Map.Entry<String, List<String>> propEntry : missingByProperty.entrySet()) {
                String propertyName = propEntry.getKey();
                List<String> missingValues = propEntry.getValue();

                writer.write("  " + propertyName + " - Missing values: " +
                    String.join(", ", missingValues) + "\n");
            }

            writer.write("\n");
        }
    }

    /**
     * Writes the duplicate definitions section
     */
    private static void writeDuplicateDefinitions(BufferedWriter writer, AnalysisReport report)
            throws IOException {
        Map<String, List<Integer>> duplicates = report.getDuplicateDefinitions();

        writer.write("----------------------------------------\n");
        writer.write("DUPLICATE DEFINITIONS:\n\n");

        if (duplicates.isEmpty()) {
            writer.write("No duplicate definitions found.\n\n");
            return;
        }

        // Sort by block ID
        List<Map.Entry<String, List<Integer>>> sortedDuplicates =
            new ArrayList<>(duplicates.entrySet());
        sortedDuplicates.sort(Map.Entry.comparingByKey());

        for (var entry : sortedDuplicates) {
            String blockState = entry.getKey();
            List<Integer> propertyIds = entry.getValue();

            Collections.sort(propertyIds);
            String propertyIdsStr = propertyIds.stream()
                .map(id -> "block." + id)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

            writer.write(blockState + " is defined multiple times:\n");
            writer.write("  Properties: " + propertyIdsStr + "\n\n");
        }
    }

    /**
     * Writes the render layer mismatches section
     */
    private static void writeRenderLayerMismatches(BufferedWriter writer, AnalysisReport report)
            throws IOException {
        Map<String, RenderLayerMismatch> mismatches = report.getRenderLayerMismatches();

        writer.write("----------------------------------------\n");
        writer.write("RENDER LAYER MISMATCHES (" + mismatches.size() + "):\n\n");

        writer.write("NOTE: The shader pack must be actively loaded in your shader loader\n");
        writer.write("(Iris, OptiFine, Oculus, etc.) at the time this report is generated\n");
        writer.write("for accurate render layer validation. If the shader is not loaded,\n");
        writer.write("mismatches may be incorrectly reported.\n\n");

        if (mismatches.isEmpty()) {
            writer.write("No render layer mismatches found.\n\n");
            return;
        }

        // Sort by block ID
        List<Map.Entry<String, RenderLayerMismatch>> sortedMismatches =
            new ArrayList<>(mismatches.entrySet());
        sortedMismatches.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, RenderLayerMismatch> entry : sortedMismatches) {
            String blockId = entry.getKey();
            RenderLayerMismatch mismatch = entry.getValue();

            writer.write(blockId + "\n");
            writer.write("Expected: " + mismatch.expected() + " | Actual: " + mismatch.actual() + "\n\n");
        }
    }
}