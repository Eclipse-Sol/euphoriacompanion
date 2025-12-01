package eclipse.euphoriacompanion.report;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Generates a list of all entities sorted by mod namespace.
 */
public class EntityListGenerator {

    /**
     * Generates and saves an entity list to the specified path
     */
    public static void generateEntityList(Path outputPath) throws IOException {
        EuphoriaCompanion.LOGGER.info("Starting entity list generation...");

        if (outputPath.getParent() == null) {
            throw new IOException("Output path has no parent directory: " + outputPath);
        }

        Files.createDirectories(outputPath.getParent());

        // Group entities by namespace (mod)
        Map<String, List<String>> entitiesByMod = new TreeMap<>();

        for (EntityType<?> entityType : Registries.ENTITY_TYPE) {
            Identifier id = Registries.ENTITY_TYPE.getId(entityType);

            String namespace = id.getNamespace();
            String entityName = id.toString();

            entitiesByMod.computeIfAbsent(namespace, k -> new ArrayList<>())
                    .add(entityName);
        }

        EuphoriaCompanion.LOGGER.info("Found {} mods with entities", entitiesByMod.size());

        // Write to temporary file first (atomic write)
        Path tempPath = outputPath.getParent().resolve(outputPath.getFileName() + ".tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            writer.write("=== ENTITY LIST ===\n");
            writer.write("All entities registered in the game, sorted by mod.\n\n");

            int totalEntities = 0;

            for (Map.Entry<String, List<String>> entry : entitiesByMod.entrySet()) {
                String modName = entry.getKey();
                List<String> entities = entry.getValue();

                // Sort entities alphabetically
                Collections.sort(entities);

                totalEntities += entities.size();

                writer.write("----------------------------------------\n");
                writer.write(modName + " (" + entities.size() + " entities):\n\n");

                for (String entity : entities) {
                    writer.write(" " + entity + "\n");
                }

                writer.write("\n");
            }

            writer.write("========================================\n");
            writer.write("TOTAL ENTITIES: " + totalEntities + "\n");
        }

        // Atomic rename - only appears as complete file
        Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        EuphoriaCompanion.LOGGER.info("Generated entity list with {} total entities at {}",
            entitiesByMod.values().stream().mapToInt(List::size).sum(), outputPath);
    }
}