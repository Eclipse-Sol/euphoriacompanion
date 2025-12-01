package eclipse.euphoriacompanion.analyzer;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.config.ModConfig;
import eclipse.euphoriacompanion.report.AnalysisReport;
import eclipse.euphoriacompanion.report.ReportGenerator;
import eclipse.euphoriacompanion.util.MinecraftVersionUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Initiates the analysis of all shaderpacks in the shaderpacks directory.
 */
public class ShaderpackAnalysisInitiator {
    private static final AtomicBoolean isProcessing = new AtomicBoolean(false);

    /**
     * Processes all shaderpacks in the shaderpacks directory
     */
    public static void processAllShaderpacks() {
        // Atomic check-and-set to prevent race conditions
        if (!isProcessing.compareAndSet(false, true)) {
            EuphoriaCompanion.LOGGER.info("Analysis already in progress, skipping request");
            return;
        }

        try {
            EuphoriaCompanion.LOGGER.info("Starting shader analysis...");

            // Get config
            ModConfig config = ModConfig.getInstance();

            // Get current MC version
            int mcVersion = MinecraftVersionUtil.getCurrentMCVersionAsInt();
            EuphoriaCompanion.LOGGER.info("Current Minecraft version: {}", mcVersion);

            // Get shaderpacks directory
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path shaderpacksDir = gameDir.resolve("shaderpacks");

            // Create shaderpacks directory if it doesn't exist (idempotent - safe to call even if exists)
            try {
                Files.createDirectories(shaderpacksDir);
            } catch (IOException e) {
                EuphoriaCompanion.LOGGER.error("Failed to create shaderpacks directory at {}", shaderpacksDir, e);
                return;
            }

            // Find all shaderpacks
            List<Path> shaderpackPaths = findShaderpacks(shaderpacksDir);

            if (shaderpackPaths.isEmpty()) {
                EuphoriaCompanion.LOGGER.error("No shaderpacks found in shaderpacks/ directory");
                return;
            }

            EuphoriaCompanion.LOGGER.info("Found {} shaderpacks: {}",
                shaderpackPaths.size(),
                getShaderpackNames(shaderpackPaths));

            // Create analyzer
            ShaderAnalyzer analyzer = new ShaderAnalyzer(config, mcVersion);

            // Create output directory (idempotent - safe to call even if exists)
            Path logsDir = gameDir.resolve("logs/euphoriacompanion");
            try {
                Files.createDirectories(logsDir);
            } catch (IOException e) {
                EuphoriaCompanion.LOGGER.error("Failed to create logs directory at {}", logsDir, e);
                return;
            }

            // Process each shaderpack
            for (Path shaderpackPath : shaderpackPaths) {
                try {
                    String shaderpackName = shaderpackPath.getFileName().toString();

                    // Analyze
                    AnalysisReport report = analyzer.analyze(shaderpackPath);

                    // Generate report file
                    String reportFileName = shaderpackName
                        .replace(".zip", "")
                        .replaceAll("[^a-zA-Z0-9._-]", "_")
                        + "_analysis.txt";

                    Path reportPath = logsDir.resolve(reportFileName);
                    ReportGenerator.generateReport(report, reportPath);

                    EuphoriaCompanion.LOGGER.info("Analysis complete. Report saved to logs/euphoriacompanion/{}",
                        reportFileName);

                } catch (IOException e) {
                    EuphoriaCompanion.LOGGER.error("Failed to analyze shaderpack: {}",
                        shaderpackPath.getFileName(), e);
                }
            }

            // Generate entity list if enabled
            if (config.generateEntityList) {
                try {
                    Path entityListPath = logsDir.resolve("entity_list.txt");
                    eclipse.euphoriacompanion.report.EntityListGenerator.generateEntityList(entityListPath);
                    EuphoriaCompanion.LOGGER.info("Entity list saved to logs/euphoriacompanion/entity_list.txt");
                } catch (IOException e) {
                    EuphoriaCompanion.LOGGER.error("Failed to generate entity list", e);
                }
            }

            EuphoriaCompanion.LOGGER.info("All shader analysis complete");

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Error during shader analysis", e);
        } finally {
            isProcessing.set(false);
        }
    }

    /**
     * Finds all shaderpacks in the shaderpacks directory
     */
    private static List<Path> findShaderpacks(Path shaderpacksDir) throws IOException {
        List<Path> shaderpacks = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shaderpacksDir)) {
            for (Path path : stream) {
                // Include directories and ZIP files
                if (Files.isDirectory(path)) {
                    // Check if it has a shaders/block.properties file
                    if (Files.exists(path.resolve("shaders/block.properties"))) {
                        shaderpacks.add(path);
                    }
                } else if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".zip")) {
                    shaderpacks.add(path);
                }
            }
        }

        return shaderpacks;
    }

    /**
     * Gets a comma-separated list of shaderpack names
     */
    private static String getShaderpackNames(List<Path> shaderpackPaths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shaderpackPaths.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(shaderpackPaths.get(i).getFileName().toString());
        }
        return sb.toString();
    }
}
