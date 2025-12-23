package eclipse.euphoriacompanion.util;

/**
 * Utility for getting the current Minecraft version as an integer.
 * Converts version like "1.21.1" to 12101.
 */
public class MinecraftVersionUtil {

    /**
     * Gets the current Minecraft version as an integer.
     * For 1.7.10, this returns a hardcoded value since the version is fixed.
     * Example: "1.7.10" -> 10710
     */
    public static int getCurrentMCVersionAsInt() {
        // We can hardcode this since we're building specifically for 1.7.10
        return 10710; // 1.7.10
    }

}