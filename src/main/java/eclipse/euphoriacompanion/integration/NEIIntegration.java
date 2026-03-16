package eclipse.euphoriacompanion.integration;

import codechicken.nei.ItemPanels;
import cpw.mods.fml.common.Optional;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.item.ItemStack;

import java.util.*;

/**
 * Integration with Not Enough Items (NEI) to capture searches.
 */
public class NEIIntegration {

    private static boolean initialized = false;

    @Optional.Method(modid = "NotEnoughItems")
    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;
        EuphoriaCompanion.LOGGER.info("NEI integration initialized successfully");
    }

    /**
     * Gets the list of items currently visible/filtered in NEI's item panel.
     * This represents what the user is currently seeing after search filtering.
     */
    @Optional.Method(modid = "NotEnoughItems")
    public static List<ItemStack> getVisibleItems() {
        // Get items from the actual item panel grid, which has the filtered results
        return new ArrayList<>(ItemPanels.itemPanel.getGrid().getItems());
    }
}
