package eclipse.euphoriacompanion;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import eclipse.euphoriacompanion.analyzer.ShaderpackAnalysisInitiator;
import eclipse.euphoriacompanion.client.ClientEventHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

@Mod(modid = EuphoriaCompanion.MODID, name = EuphoriaCompanion.NAME, version = EuphoriaCompanion.VERSION)
public class EuphoriaCompanion {
    public static final String MODID = "euphoriacompanion";
    public static final String NAME = "Euphoria Companion";
    public static final String VERSION = "2.0.1";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private static final AtomicLong lastAnalysisTime = new AtomicLong(0);
    private static final long COOLDOWN_MS = 2000;

    public static void processShaderPacks() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastAnalysis = currentTime - lastAnalysisTime.get();

        if (timeSinceLastAnalysis < COOLDOWN_MS) {
            long remainingCooldown = (COOLDOWN_MS - timeSinceLastAnalysis + 999) / 1000;
            LOGGER.info("Analysis on cooldown, please wait {} second{}", remainingCooldown, remainingCooldown == 1 ? "" : "s");
            return;
        }

        lastAnalysisTime.set(currentTime);

        Thread analysisThread = new Thread(() -> {
            try {
                ShaderpackAnalysisInitiator.processAllShaderpacks();
            } catch (Exception e) {
                LOGGER.error("Error in shader analysis thread", e);
            }
        }, "EuphoriaCompanion-Analysis");

        analysisThread.setDaemon(true);
        analysisThread.start();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Only register client-side components on the client to avoid server crashes
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            ClientEventHandler.registerKeyBinding();
            FMLCommonHandler.instance().bus().register(new ClientEventHandler());

            // Initialize NEI integration if NEI is loaded
            if (cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems")) {
                try {
                    eclipse.euphoriacompanion.integration.NEIIntegration.init();
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize NEI integration", e);
                }
            }
        }
    }

}
