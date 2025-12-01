package eclipse.euphoriacompanion.config;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration for Euphoria Companion mod.
 * Handles loading and saving of mod settings.
 */
public class ModConfig {
    private static final String CONFIG_FILE_NAME = "euphoriacompanion.properties";

    private static ModConfig instance;

    // Config fields with default values
    public ScanMode scanMode = ScanMode.DEEP;
    public TagSupportMode tagSupport = TagSupportMode.DETECT;
    public boolean validateRenderLayers = true;
    public boolean checkLightEmitting = true;
    public boolean checkTranslucent = true;
    public boolean checkNonFull = true;
    public boolean checkFull = true;
    public boolean checkBlockEntity = true;
    public boolean generateEntityList = true;

    // Cached detection results
    private Boolean cachedIrisSupport = null;
    private Boolean cachedEuphoriaPatchesSupport = null;

    /**
     * Scan mode for block state analysis.
     */
    public enum ScanMode {
        QUICK,  // Default state only (fast)
        DEEP    // All block states (slow but thorough)
    }

    /**
     * Tag support mode.
     */
    public enum TagSupportMode {
        DETECT,  // Auto-enable if Iris 1.8+ is loaded
        TRUE,    // Force enable
        FALSE    // Force disable
    }

    /**
     * Gets the singleton instance of the config
     */
    public static ModConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Gets the config file path
     */
    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    /**
     * Loads the config from file, or creates a new one with defaults.
     * Validates the loaded config and resets to defaults if invalid.
     */
    private static ModConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                Properties props = new Properties();
                props.load(reader);

                ModConfig config = new ModConfig();
                config.loadFromProperties(props);

                // Validate loaded config
                if (!config.validate()) {
                    EuphoriaCompanion.LOGGER.warn("Invalid or corrupted config file, resetting to defaults");
                    config = createDefaultConfig(configPath);
                } else {
                    EuphoriaCompanion.LOGGER.info("Loaded configuration from {}", configPath);
                }

                return config;
            } catch (Exception e) {
                EuphoriaCompanion.LOGGER.error("Failed to load config, resetting to defaults", e);
                return createDefaultConfig(configPath);
            }
        }

        // Create new config with defaults
        return createDefaultConfig(configPath);
    }

    /**
     * Creates a default config and saves it to disk
     */
    private static ModConfig createDefaultConfig(Path configPath) {
        ModConfig config = new ModConfig();
        config.save();
        EuphoriaCompanion.LOGGER.info("Created default configuration at {}", configPath);
        return config;
    }

    /**
     * Validates that all config values are valid.
     * Returns false if any values are null or invalid.
     */
    private boolean validate() {
        if (scanMode == null) {
            EuphoriaCompanion.LOGGER.error("Invalid config: scanMode is null (valid values: QUICK, DEEP)");
            return false;
        }
        if (tagSupport == null) {
            EuphoriaCompanion.LOGGER.error("Invalid config: tagSupport is null (valid values: DETECT, TRUE, FALSE)");
            return false;
        }
        return true;
    }

    /**
     * Loads values from a Properties object
     */
    private void loadFromProperties(Properties props) {
        try {
            String scanModeStr = props.getProperty("scanMode");
            if (scanModeStr != null) {
                scanMode = ScanMode.valueOf(scanModeStr);
            }
        } catch (IllegalArgumentException e) {
            EuphoriaCompanion.LOGGER.warn("Invalid scanMode value, using default: {}", scanMode);
        }

        try {
            String tagSupportStr = props.getProperty("tagSupport");
            if (tagSupportStr != null) {
                tagSupport = TagSupportMode.valueOf(tagSupportStr);
            }
        } catch (IllegalArgumentException e) {
            EuphoriaCompanion.LOGGER.warn("Invalid tagSupport value, using default: {}", tagSupport);
        }

        validateRenderLayers = Boolean.parseBoolean(props.getProperty("validateRenderLayers", "true"));
        checkLightEmitting = Boolean.parseBoolean(props.getProperty("checkLightEmitting", "true"));
        checkTranslucent = Boolean.parseBoolean(props.getProperty("checkTranslucent", "true"));
        checkNonFull = Boolean.parseBoolean(props.getProperty("checkNonFull", "true"));
        checkFull = Boolean.parseBoolean(props.getProperty("checkFull", "true"));
        checkBlockEntity = Boolean.parseBoolean(props.getProperty("checkBlockEntity", "true"));
        generateEntityList = Boolean.parseBoolean(props.getProperty("generateEntityList", "false"));
    }

    /**
     * Saves the current config to file
     */
    public void save() {
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                Properties props = new Properties();

                // Add header comment
                writer.write("# Euphoria Companion Configuration\n");

                writer.write("# Scan mode for block state analysis\n");
                writer.write("# Valid values: QUICK (fast, default state only), DEEP (slow but thorough, all block states)\n");
                props.setProperty("scanMode", scanMode.name());

                writer.write("\n# Tag support mode\n");
                writer.write("# Valid values: DETECT (auto-enable if Iris 1.8+), TRUE (force enable), FALSE (force disable)\n");
                props.setProperty("tagSupport", tagSupport.name());

                writer.write("\n# Validation and categorization options\n");
                writer.write("# Note: Block entities may not all use entity rendering (gbuffers_entities)\n");
                props.setProperty("checkLightEmitting", String.valueOf(checkLightEmitting));
                props.setProperty("checkTranslucent", String.valueOf(checkTranslucent));
                props.setProperty("checkNonFull", String.valueOf(checkNonFull));
                props.setProperty("checkFull", String.valueOf(checkFull));
                props.setProperty("checkBlockEntity", String.valueOf(checkBlockEntity));
                props.setProperty("validateRenderLayers", String.valueOf(validateRenderLayers));

                writer.write("\n# Generate entity list file\n");
                writer.write("# When enabled, generates a separate entity_list.txt file with all entities sorted by mod\n");
                props.setProperty("generateEntityList", String.valueOf(generateEntityList));

                // Write properties without the default timestamp comment
                props.store(writer, null);

                EuphoriaCompanion.LOGGER.info("Saved configuration to {}", configPath);
            }
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * Determines if tag support should be enabled based on the current mode
     */
    public boolean isTagSupportEnabled() {
        return switch (tagSupport) {
            case TRUE -> true;
            case FALSE -> false;
            case DETECT -> {
                if (cachedIrisSupport == null) {
                    cachedIrisSupport = detectIrisSupport();
                }
                yield cachedIrisSupport;
            }
        };
    }

    /**
     * Detects if Iris 1.8+ is loaded (called once and cached)
     */
    private boolean detectIrisSupport() {
        return FabricLoader.getInstance().getModContainer("iris")
            .map(modContainer -> {
                String version = modContainer.getMetadata().getVersion().getFriendlyString();
                EuphoriaCompanion.LOGGER.info("Detected Iris version: {}", version);

                // Parse version string (e.g., "1.8.0", "1.8.1+mc1.21.1")
                try {
                    String[] parts = version.split("[.+]");
                    if (parts.length >= 2) {
                        int major = Integer.parseInt(parts[0]);
                        int minor = Integer.parseInt(parts[1]);
                        boolean supported = major > 1 || (major == 1 && minor >= 8);

                        if (supported) {
                            EuphoriaCompanion.LOGGER.info("Iris 1.8+ detected, enabling tag support");
                        } else {
                            EuphoriaCompanion.LOGGER.info("Iris version < 1.8, tag support disabled");
                        }

                        return supported;
                    }
                } catch (NumberFormatException e) {
                    EuphoriaCompanion.LOGGER.warn("Failed to parse Iris version: {}", version);
                }

                return false;
            })
            .orElseGet(() -> {
                EuphoriaCompanion.LOGGER.info("Iris not detected, tag support disabled");
                return false;
            });
    }

    /**
     * Detects if Euphoria Patches 1.7.8+ is loaded (required for Euphoria Companion defines)
     * Result is cached after first call
     */
    public boolean detectEuphoriaPatchesSupport() {
        if (cachedEuphoriaPatchesSupport != null) {
            return cachedEuphoriaPatchesSupport;
        }

        cachedEuphoriaPatchesSupport = detectEuphoriaPatchesSupportInternal();
        return cachedEuphoriaPatchesSupport;
    }

    /**
     * Internal method that actually performs the Euphoria Patches detection
     */
    private boolean detectEuphoriaPatchesSupportInternal() {
        return FabricLoader.getInstance().getModContainer("euphoria_patcher")
            .map(modContainer -> {
                String version = modContainer.getMetadata().getVersion().getFriendlyString();
                EuphoriaCompanion.LOGGER.info("Detected Euphoria Patches version: {}", version);

                // Parse version string (e.g., "1.7.8", "1.7.8-r5.6.1-fabric", "1.7.9+mc1.21")
                try {
                    // Extract just the first part before any suffix (e.g., "1.7.8" from "1.7.8-r5.6.1-fabric")
                    String versionCore = version.split("-")[0].split("\\+")[0];
                    String[] parts = versionCore.split("\\.");
                    if (parts.length >= 3) {
                        int major = Integer.parseInt(parts[0]);
                        int minor = Integer.parseInt(parts[1]);
                        int patch = Integer.parseInt(parts[2]);

                        boolean supported = major > 1 ||
                                          (major == 1 && minor > 7) ||
                                          (major == 1 && minor == 7 && patch >= 8);

                        if (supported) {
                            EuphoriaCompanion.LOGGER.info("Euphoria Patches 1.7.8+ detected, enabling Euphoria Companion defines");
                        } else {
                            EuphoriaCompanion.LOGGER.info("Euphoria Patches version < 1.7.8, defines disabled");
                        }

                        return supported;
                    }
                } catch (NumberFormatException e) {
                    EuphoriaCompanion.LOGGER.warn("Failed to parse Euphoria Patches version: {}", version);
                }

                return false;
            })
            .orElse(false);
    }
}
