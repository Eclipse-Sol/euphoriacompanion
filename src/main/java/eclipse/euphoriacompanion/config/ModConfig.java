package eclipse.euphoriacompanion.config;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import eclipse.euphoriacompanion.EuphoriaCompanion;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ModConfig {
    private static final String CONFIG_FILE_NAME = "euphoriacompanion.properties";

    private static ModConfig instance;

    public ScanMode scanMode = ScanMode.DEEP;
    public boolean checkLightEmitting = true;
    public boolean checkTranslucent = true;
    public boolean checkNonFull = true;
    public boolean checkFull = true;
    public boolean checkBlockEntity = true;
    public boolean generateEntityList = true;
    public boolean generateUnregisteredEntityScan = false;
    public boolean quoteBlockIds = true;

    private Boolean cachedEuphoriaPatcherSupport = null;

    public static ModConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path getConfigPath() {
        File configDir = new File("config");
        return configDir.toPath().resolve(CONFIG_FILE_NAME);
    }

    private static ModConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                Properties props = new Properties();
                props.load(reader);

                ModConfig config = new ModConfig();
                config.loadFromProperties(props);

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

        return createDefaultConfig(configPath);
    }

    private static ModConfig createDefaultConfig(Path configPath) {
        ModConfig config = new ModConfig();
        config.save();
        EuphoriaCompanion.LOGGER.info("Created default configuration at {}", configPath);
        return config;
    }

    private boolean validate() {
        if (scanMode == null) {
            EuphoriaCompanion.LOGGER.error("Invalid config: scanMode is null (valid values: QUICK, DEEP)");
            return false;
        }
        return true;
    }

    private void loadFromProperties(Properties props) {
        try {
            String scanModeStr = props.getProperty("scanMode");
            if (scanModeStr != null) {
                scanMode = ScanMode.valueOf(scanModeStr);
            }
        } catch (IllegalArgumentException e) {
            EuphoriaCompanion.LOGGER.warn("Invalid scanMode value, using default: {}", scanMode);
        }

        checkLightEmitting = Boolean.parseBoolean(props.getProperty("checkLightEmitting", "true"));
        checkTranslucent = Boolean.parseBoolean(props.getProperty("checkTranslucent", "true"));
        checkNonFull = Boolean.parseBoolean(props.getProperty("checkNonFull", "true"));
        checkFull = Boolean.parseBoolean(props.getProperty("checkFull", "true"));
        checkBlockEntity = Boolean.parseBoolean(props.getProperty("checkBlockEntity", "true"));
        generateEntityList = Boolean.parseBoolean(props.getProperty("generateEntityList", "false"));
        generateUnregisteredEntityScan = Boolean.parseBoolean(props.getProperty("generateUnregisteredEntityScan", "false"));
        quoteBlockIds = Boolean.parseBoolean(props.getProperty("quoteBlockIds", "true"));
    }

    public void save() {
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                Properties props = new Properties();

                props.setProperty("scanMode", scanMode.name());
                props.setProperty("checkLightEmitting", String.valueOf(checkLightEmitting));
                props.setProperty("checkTranslucent", String.valueOf(checkTranslucent));
                props.setProperty("checkNonFull", String.valueOf(checkNonFull));
                props.setProperty("checkFull", String.valueOf(checkFull));
                props.setProperty("checkBlockEntity", String.valueOf(checkBlockEntity));
                props.setProperty("generateEntityList", String.valueOf(generateEntityList));
                props.setProperty("generateUnregisteredEntityScan", String.valueOf(generateUnregisteredEntityScan));
                props.setProperty("quoteBlockIds", String.valueOf(quoteBlockIds));

                String header = "Euphoria Companion Configuration\n\n" +
                        "Scan mode for block state analysis\n" +
                        "Valid values: QUICK (fast, default state only), DEEP (slow but thorough, all block states)\n\n" +
                        "Validation and categorization options\n" +
                        "Note: Block entities may not all use entity rendering (gbuffers_entities)\n\n" +
                        "Generate entity list file\n" +
                        "When enabled, generates a separate entity_list.txt file with all entities sorted by mod\n\n" +
                        "Generate unregistered entity scan\n" +
                        "When enabled, scans ALL Entity classes on classpath and shows only unregistered entities\n" +
                        "Output: unregistered_entity_scan.txt (Warning: may be slow on large modpacks)\n\n" +
                        "Quote block and entity IDs\n" +
                        "When enabled, IDs with spaces or special characters are quoted and escaped (e.g., \"mod:block name\")";

                props.store(writer, header);

                EuphoriaCompanion.LOGGER.info("Saved configuration to {}", configPath);
            }
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to save config", e);
        }
    }

    public boolean detectEuphoriaPatcherSupport() {
        if (cachedEuphoriaPatcherSupport != null) {
            return cachedEuphoriaPatcherSupport;
        }

        cachedEuphoriaPatcherSupport = detectEuphoriaPatcherSupportInternal();
        return cachedEuphoriaPatcherSupport;
    }

    private boolean detectEuphoriaPatcherSupportInternal() {
        if (!Loader.isModLoaded("euphoria_patcher")) {
            return false;
        }

        ModContainer euphoriaPatcher = Loader.instance().getIndexedModList().get("euphoria_patcher");
        if (euphoriaPatcher == null) {
            return false;
        }

        String version = euphoriaPatcher.getVersion();
        EuphoriaCompanion.LOGGER.info("Detected Euphoria Patcher version: {}", version);

        try {
            // Handle leading underscore in version strings like "_1.7.8"
            String cleanVersion = version.startsWith("_") ? version.substring(1) : version;

            // Extract version core from strings like "1.7.8-r5.6.1-fabric" or "1.7.9+mc1.21"
            String versionCore = cleanVersion.split("-")[0].split("\\+")[0];
            String[] parts = versionCore.split("\\.");
            if (parts.length >= 3) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                int patch = Integer.parseInt(parts[2]);

                boolean supported = major > 1 ||
                        (major == 1 && minor > 7) ||
                        (major == 1 && minor == 7 && patch >= 8);

                if (supported) {
                    EuphoriaCompanion.LOGGER.info("Euphoria Patcher 1.7.8+ detected, enabling Euphoria Companion defines");
                } else {
                    EuphoriaCompanion.LOGGER.info("Euphoria Patcher version < 1.7.8, defines disabled");
                }

                return supported;
            }
        } catch (NumberFormatException e) {
            EuphoriaCompanion.LOGGER.warn("Failed to parse Euphoria Patcher version: {}", version);
        }

        return false;
    }

    public enum ScanMode {
        QUICK,
        DEEP
    }
}
