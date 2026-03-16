package eclipse.euphoriacompanion.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.util.Objects;

@SideOnly(Side.CLIENT)
public class ClientEventHandler {

    public static KeyBinding analyzeKey;
    public static KeyBinding dumpNEIItemsKey;
    public static KeyBinding dumpInventoryKey;
    private boolean wasNEIItemsKeyPressed = false;
    private boolean wasInventoryKeyPressed = false;

    public static void registerKeyBinding() {
        analyzeKey = new KeyBinding(
                "key.euphoriacompanion.analyze",
                Keyboard.KEY_MULTIPLY,  // Numpad *
                "key.categories.euphoriacompanion"
        );

        dumpNEIItemsKey = new KeyBinding(
                "key.euphoriacompanion.dumpnei",
                Keyboard.KEY_SUBTRACT,  // Numpad -
                "key.categories.euphoriacompanion"
        );

        dumpInventoryKey = new KeyBinding(
                "key.euphoriacompanion.dumpinventory",
                Keyboard.KEY_ADD,  // Numpad +
                "key.categories.euphoriacompanion"
        );

        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(analyzeKey);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(dumpNEIItemsKey);
        cpw.mods.fml.client.registry.ClientRegistry.registerKeyBinding(dumpInventoryKey);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();

        if (mc.thePlayer != null && mc.currentScreen == null && !mc.isGamePaused()) {
            if (analyzeKey != null && analyzeKey.isPressed()) {
                EuphoriaCompanion.processShaderPacks();
            }
        }

        if (mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null) {
            if (dumpNEIItemsKey != null && dumpNEIItemsKey.isPressed()) {
                dumpTrackedNEIItems();
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen == null) {
            wasNEIItemsKeyPressed = false;
            wasInventoryKeyPressed = false;
            return;
        }

        // Check keyboard state directly, not sure why other methods aren't working
        if (dumpNEIItemsKey != null) {
            boolean isNEIItemsKeyDown = Keyboard.isKeyDown(dumpNEIItemsKey.getKeyCode());

            // Don't spam action
            if (isNEIItemsKeyDown && !wasNEIItemsKeyPressed) {
                dumpTrackedNEIItems();
            }

            wasNEIItemsKeyPressed = isNEIItemsKeyDown;
        }

        if (dumpInventoryKey != null) {
            boolean isInventoryKeyDown = Keyboard.isKeyDown(dumpInventoryKey.getKeyCode());

            if (isInventoryKeyDown && !wasInventoryKeyPressed) {
                dumpInventoryItems();
            }

            wasInventoryKeyPressed = isInventoryKeyDown;
        }
    }

    private void dumpTrackedNEIItems() {
        if (!cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems")) {
            EuphoriaCompanion.LOGGER.info("NEI is not loaded, cannot capture items");
            return;
        }

        try {
            java.util.List<net.minecraft.item.ItemStack> visibleStacks =
                eclipse.euphoriacompanion.integration.NEIIntegration.getVisibleItems();

            if (visibleStacks.isEmpty()) {
                EuphoriaCompanion.LOGGER.info("No items currently visible in NEI");
                return;
            }

            java.util.List<String> formattedItems = formatItemIds(visibleStacks);

            EuphoriaCompanion.LOGGER.info("Captured {} items from current NEI search", formattedItems.size());

            // Write to file
            writeNEIItemsReport(formattedItems);
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to capture NEI items", e);
        }
    }

    private void writeNEIItemsReport(java.util.List<String> items) {
        try {
            java.nio.file.Path reportDir = java.nio.file.Paths.get("logs", "euphoriacompanion");
            java.nio.file.Files.createDirectories(reportDir);

            java.nio.file.Path reportFile = reportDir.resolve("nei_tracked_items.txt");

            try (java.io.PrintWriter writer = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(reportFile))) {
                writer.println("# NEI Search Capture Report");
                writer.println("# Generated: " + java.time.LocalDateTime.now());
                writer.println("# Items visible in NEI at time of capture: " + items.size());
                writer.println();
                writer.println("# These are the items currently matching your NEI search");
                writer.println("# You can use this to identify which items should be added to item.properties");
                writer.println();

                for (String item : items) {
                    writer.println(item);
                }
            }

            EuphoriaCompanion.LOGGER.info("Wrote NEI search capture to: {}", reportFile.toAbsolutePath());
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to write NEI items report", e);
        }
    }

    private void dumpInventoryItems() {
        Minecraft mc = Minecraft.getMinecraft();

        if (mc.thePlayer == null) {
            EuphoriaCompanion.LOGGER.info("No player available to dump inventory");
            return;
        }

        try {
            java.util.List<net.minecraft.item.ItemStack> inventoryStacks = new java.util.ArrayList<>();

            // Iterate through all inventory slots
            for (int i = 0; i < mc.thePlayer.inventory.getSizeInventory(); i++) {
                net.minecraft.item.ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null) {
                    inventoryStacks.add(stack);
                }
            }

            if (inventoryStacks.isEmpty()) {
                EuphoriaCompanion.LOGGER.info("No items in inventory");
                return;
            }

            java.util.List<String> formattedItems = formatItemIds(inventoryStacks);

            EuphoriaCompanion.LOGGER.info("Captured {} unique items from inventory", formattedItems.size());
            writeInventoryReport(formattedItems);
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to dump inventory items", e);
        }
    }

    private void writeInventoryReport(java.util.List<String> items) {
        try {
            java.nio.file.Path reportDir = java.nio.file.Paths.get("logs", "euphoriacompanion");
            java.nio.file.Files.createDirectories(reportDir);

            java.nio.file.Path reportFile = reportDir.resolve("inventory_items.txt");

            try (java.io.PrintWriter writer = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(reportFile))) {
                writer.println("# Player Inventory Report");
                writer.println("# Generated: " + java.time.LocalDateTime.now());
                writer.println("# Unique items in inventory: " + items.size());
                writer.println();
                writer.println("# These are all unique items currently in your inventory");
                writer.println("# You can use this to identify which items should be added to item.properties");
                writer.println();

                for (String item : items) {
                    writer.println(item);
                }
            }

            EuphoriaCompanion.LOGGER.info("Wrote inventory report to: {}", reportFile.toAbsolutePath());
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to write inventory report", e);
        }
    }

    private String getItemId(net.minecraft.item.ItemStack stack) {
        if (stack == null) {
            return "null";
        }

        String registryName = Objects.requireNonNull(stack.getItem()).delegate.name();
        int metadata = stack.getItemDamage();

        return registryName + ":" + metadata;
    }

    private String getBaseItemId(net.minecraft.item.ItemStack stack) {
        if (stack == null) {
            return "null";
        }

        return Objects.requireNonNull(stack.getItem()).delegate.name();
    }

    private java.util.List<String> formatItemIds(java.util.List<net.minecraft.item.ItemStack> stacks) {
        // Group items by base ID
        java.util.Map<String, java.util.List<Integer>> baseToMetadata = new java.util.HashMap<>();

        for (net.minecraft.item.ItemStack stack : stacks) {
            if (stack != null) {
                String baseId = getBaseItemId(stack);
                int metadata = stack.getItemDamage();

                baseToMetadata.computeIfAbsent(baseId, k -> new java.util.ArrayList<>()).add(metadata);
            }
        }

        // Build final list
        java.util.Set<String> result = new java.util.LinkedHashSet<>();

        for (java.util.Map.Entry<String, java.util.List<Integer>> entry : baseToMetadata.entrySet()) {
            String baseId = entry.getKey();
            java.util.List<Integer> metadataList = entry.getValue();

            // Remove duplicates from metadata list
            java.util.Set<Integer> uniqueMetadata = new java.util.LinkedHashSet<>(metadataList);

            if (uniqueMetadata.size() > 1) {
                // Multiple metadata variants - include metadata for each
                for (Integer metadata : uniqueMetadata) {
                    result.add(baseId + ":" + metadata);
                }
            } else {
                result.add(baseId);
            }
        }

        return new java.util.ArrayList<>(result);
    }
}