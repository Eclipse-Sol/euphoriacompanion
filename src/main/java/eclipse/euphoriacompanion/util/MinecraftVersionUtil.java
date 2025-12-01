package eclipse.euphoriacompanion.util;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Utility for getting the current Minecraft version as an integer.
 * Converts version like "1.21.1" to 12101.
 */
public class MinecraftVersionUtil {

    /**
     * Gets the current Minecraft version as an integer.
     * Example: "1.21.1" -> 12101, "1.20.1" -> 12001, "1.7.10" -> 10710
     */
    public static int getCurrentMCVersionAsInt() {
        try {
            String version = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow(() -> new RuntimeException("Minecraft mod container not found"))
                .getMetadata()
                .getVersion()
                .getFriendlyString();

            return parseVersionToInt(version);
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to determine Minecraft version", e);
            throw new RuntimeException("Unable to determine Minecraft version", e);
        }
    }

    /**
     * Parses a version string to an integer.
     * Example: "1.21.1" -> 12101, "1.20.1" -> 12001
     * Snapshots (e.g., "24w14a") are treated as the latest version.
     * Throws RuntimeException if version cannot be parsed.
     */
    public static int parseVersionToInt(String version) {
        // Check if it's a snapshot (format: YYwWWx like "24w14a")
        if (version.matches("\\d{2}w\\d{2}[a-z]")) {
            EuphoriaCompanion.LOGGER.info("Detected snapshot version: {}, treating as latest", version);
            return Integer.MAX_VALUE; // Treat as latest/newest version
        }

        // Remove any extra parts (e.g., "1.21.1-pre1" -> "1.21.1")
        String[] mainParts = version.split("-")[0].split("\\+")[0].split("\\.");

        if (mainParts.length < 2) {
            throw new RuntimeException("Invalid Minecraft version format: " + version + " (expected format: X.Y or X.Y.Z), what happened!!");
        }

        try {
            int major = Integer.parseInt(mainParts[0]);
            int minor = Integer.parseInt(mainParts[1]);
            int patch = mainParts.length >= 3 ? Integer.parseInt(mainParts[2]) : 0;

            // Format: ABBCC where A=major, BB=minor (padded), CC=patch (padded)
            // Examples: 1.21.1 -> 12101, 1.20.1 -> 12001, 1.7.10 -> 10710
            return major * 10000 + minor * 100 + patch;

        } catch (NumberFormatException e) {
            throw new RuntimeException("Failed to parse Minecraft version: " + version + " - version parts must be numeric", e);
        }
    }
}
