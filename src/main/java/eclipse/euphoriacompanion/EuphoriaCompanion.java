package eclipse.euphoriacompanion;

import eclipse.euphoriacompanion.analyzer.ShaderpackAnalysisInitiator;
import eclipse.euphoriacompanion.config.ModConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EuphoriaCompanion implements ModInitializer {
    public static final String MODID = "EuphoriaCompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    public static KeyBinding ANALYZE_KEY;

    private static long lastAnalysisTime = 0;
    private static final long COOLDOWN_MS = 2000; // 2 seconds

    /**
     * Process all shader packs in the game directory.
     * Runs on a separate thread to avoid blocking the main Minecraft thread.
     * Has a 2-second cooldown to prevent accidental spam.
     */
    public static void processShaderPacks() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastAnalysis = currentTime - lastAnalysisTime;

        if (timeSinceLastAnalysis < COOLDOWN_MS) {
            long remainingCooldown = (COOLDOWN_MS - timeSinceLastAnalysis) / 1000;
            LOGGER.info("Analysis on cooldown, please wait {} seconds", remainingCooldown + 1);
            return;
        }

        lastAnalysisTime = currentTime;

        Thread analysisThread = new Thread(() -> {
            try {
                ShaderpackAnalysisInitiator.processAllShaderpacks();
            } catch (Exception e) {
                LOGGER.error("Error in shader analysis thread", e);
            }
        }, "EuphoriaCompanion-Analysis");

        analysisThread.setDaemon(true); // Daemon thread won't prevent game shutdown
        analysisThread.start();
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Euphoria Companion");

        // Load configuration
        ModConfig config = ModConfig.getInstance();
        LOGGER.info("Tag support: {}", config.isTagSupportEnabled() ? "enabled" : "disabled");

        try {
            // Register the keybinding using Fabric API
            ANALYZE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.euphoriacompanion.analyze",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                "category.euphoriacompanion.keys"));
        } catch (Exception e) {
            LOGGER.error("Failed to register keybinding", e);
        }
    }
}