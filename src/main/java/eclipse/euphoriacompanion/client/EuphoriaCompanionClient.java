package eclipse.euphoriacompanion.client;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client initializer for the Euphoria Companion mod.
 * Keybinding is handled through mixins to ensure compatibility across Fabric versions.
 */
public class EuphoriaCompanionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EuphoriaCompanion.LOGGER.info("Initializing Euphoria Companion Client");
    }
}