package eclipse.euphoriacompanion.report;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.util.BlockIdFormatter;
import net.minecraft.entity.EntityList;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Generates a list of all entities sorted by mod namespace.
 * Adapted for Minecraft 1.7.10 EntityList system.
 */
public class EntityListGenerator {

    /**
     * Generates and saves an entity list to the specified path
     */
    public static void generateEntityList(Path outputPath, boolean quoteEntityIds) throws IOException {
        EuphoriaCompanion.LOGGER.info("Starting entity list generation...");

        if (outputPath.getParent() == null) {
            throw new IOException("Output path has no parent directory: " + outputPath);
        }

        Files.createDirectories(outputPath.getParent());

        Map<String, List<String>> entitiesByMod = getStringListMap();

        EuphoriaCompanion.LOGGER.info("Found {} mods with entities", entitiesByMod.size());

        // Write to temporary file first (atomic write)
        Path tempPath = outputPath.getParent().resolve(outputPath.getFileName() + ".tmp");

        int totalEntities;
        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            writer.write("=== ENTITY LIST ===\n");
            writer.write("All entities registered in the game, sorted by mod.\n\n");

            totalEntities = 0;

            for (Map.Entry<String, List<String>> entry : entitiesByMod.entrySet()) {
                String modName = entry.getKey();
                List<String> entities = entry.getValue();

                Collections.sort(entities);

                totalEntities += entities.size();

                writer.write("----------------------------------------\n");
                writer.write(modName + " (" + entities.size() + " entities):\n\n");

                for (String entity : entities) {
                    writer.write(" " + BlockIdFormatter.formatId(entity, quoteEntityIds) + "\n");
                }

                writer.write("\n");
            }

            writer.write("========================================\n");
            writer.write("TOTAL ENTITIES: " + totalEntities + "\n");
        }

        // Atomic rename - only appears as complete file
        Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        EuphoriaCompanion.LOGGER.info("Generated entity list with {} total entities at {}", totalEntities, outputPath);
    }

    private static Map<String, List<String>> getStringListMap() {
        Map<String, List<String>> entitiesByMod = new TreeMap<>();

        // In 1.7.10, EntityList.stringToClassMapping contains all registered entities
        for (Object entityNameObj : EntityList.stringToClassMapping.keySet()) {
            if (!(entityNameObj instanceof String)) {
                continue;
            }

            String entityName = (String) entityNameObj;

            // Format in 1.7.10 is either "EntityName" or "modid.EntityName"
            String namespace = "minecraft";
            if (entityName.contains(".")) {
                String[] parts = entityName.split("\\.", 2);
                namespace = parts[0];
            }

            List<String> entitiesForMod = entitiesByMod.computeIfAbsent(namespace, k -> new ArrayList<>());

            entitiesForMod.add(entityName);
        }
        return entitiesByMod;
    }
}