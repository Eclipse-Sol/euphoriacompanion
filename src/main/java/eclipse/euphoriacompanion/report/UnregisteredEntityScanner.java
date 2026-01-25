package eclipse.euphoriacompanion.report;

import cpw.mods.fml.common.Loader;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Scans for all Entity classes on the classpath and reports only unregistered entities.
 * Registered entities are excluded from the report.
 */
public class UnregisteredEntityScanner {

    /**
     * Generates an unregistered entity report
     */
    public static void generateUnregisteredReport(Path outputPath, boolean quoteEntityIds) throws IOException {
        EuphoriaCompanion.LOGGER.info("Starting unregistered entity scan...");

        if (outputPath.getParent() == null) {
            throw new IOException("Output path has no parent directory: " + outputPath);
        }

        Files.createDirectories(outputPath.getParent());

        // Get all registered entities
        Map<Class<?>, String> registeredEntities = getRegisteredEntities();
        EuphoriaCompanion.LOGGER.info("Found {} registered entity classes", registeredEntities.size());

        // Find all Entity classes on classpath
        Set<Class<?>> allEntityClasses = findAllEntityClasses();
        EuphoriaCompanion.LOGGER.info("Found {} total Entity classes on classpath", allEntityClasses.size());

        // Filter to only unregistered entities
        Map<String, EntityInfo> unregisteredEntitiesByClass = filterUnregisteredEntities(allEntityClasses, registeredEntities, quoteEntityIds);

        // Write report
        Path tempPath = outputPath.getParent().resolve(outputPath.getFileName() + ".tmp");
        writeReport(tempPath, unregisteredEntitiesByClass, quoteEntityIds);

        // Atomic rename
        Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        EuphoriaCompanion.LOGGER.info("Generated unregistered entity report at {}", outputPath);
    }

    /**
     * Gets all registered entities from EntityList
     */
    private static Map<Class<?>, String> getRegisteredEntities() {
        Map<Class<?>, String> registered = new HashMap<>();

        // EntityList.classToStringMapping maps entity classes to their registry names
        for (Object entry : EntityList.classToStringMapping.entrySet()) {
            if (entry instanceof Map.Entry) {
                Map.Entry<?, ?> mapEntry = (Map.Entry<?, ?>) entry;
                Object classObj = mapEntry.getKey();
                Object nameObj = mapEntry.getValue();

                if (classObj instanceof Class && nameObj instanceof String) {
                    registered.put((Class<?>) classObj, (String) nameObj);
                }
            }
        }

        return registered;
    }

    /**
     * Finds all Entity classes by enumerating loaded classes from the classloader
     */
    private static Set<Class<?>> findAllEntityClasses() {
        Set<Class<?>> entityClasses = new HashSet<>();

        EuphoriaCompanion.LOGGER.info("Enumerating all loaded classes from classloader...");

        List<Class<?>> allLoadedClasses = getAllLoadedClasses();
        EuphoriaCompanion.LOGGER.info("Found {} loaded classes, checking which extend Entity...", allLoadedClasses.size());

        int checked = 0;
        for (Class<?> clazz : allLoadedClasses) {
            checked++;
            if (checked % 1000 == 0) {
                EuphoriaCompanion.LOGGER.info("Checked {}/{} classes...", checked, allLoadedClasses.size());
            }

            if (isEntityClass(clazz)) {
                entityClasses.add(clazz);
            }
        }

        return entityClasses;
    }

    /**
     * Gets all loaded classes from the classloader using reflection
     */
    private static List<Class<?>> getAllLoadedClasses() {
        List<Class<?>> allClasses = new ArrayList<>();

        try {
            // Get the mod classloader
            ClassLoader classLoader = Loader.instance().getModClassLoader();

            // ModClassLoader has a mainClassLoader field that's the actual LaunchClassLoader
            ClassLoader launchClassLoader = classLoader;
            try {
                java.lang.reflect.Field mainClassLoaderField = classLoader.getClass().getDeclaredField("mainClassLoader");
                mainClassLoaderField.setAccessible(true);
                launchClassLoader = (ClassLoader) mainClassLoaderField.get(classLoader);
            } catch (Exception e) {
                EuphoriaCompanion.LOGGER.warn("Could not access mainClassLoader field: {}", e.getMessage());
            }

            // Access LaunchClassLoader's cachedClasses field
            try {
                java.lang.reflect.Field cachedClassesField = launchClassLoader.getClass().getDeclaredField("cachedClasses");
                cachedClassesField.setAccessible(true);
                Object cachedClasses = cachedClassesField.get(launchClassLoader);

                if (cachedClasses instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Class<?>> classMap = (Map<String, Class<?>>) cachedClasses;
                    allClasses.addAll(classMap.values());
                    EuphoriaCompanion.LOGGER.info("Retrieved {} classes from LaunchClassLoader", classMap.size());
                }
            } catch (NoSuchFieldException e) {
                EuphoriaCompanion.LOGGER.error("Could not access cachedClasses field from LaunchClassLoader", e);
            }

            // Try standard ClassLoader.classes field as fallback
            if (allClasses.isEmpty()) {
                try {
                    java.lang.reflect.Field classesField = ClassLoader.class.getDeclaredField("classes");
                    classesField.setAccessible(true);

                    @SuppressWarnings("unchecked")
                    Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(classLoader);

                    if (classes != null) {
                        allClasses.addAll(classes);
                        EuphoriaCompanion.LOGGER.info("Retrieved {} classes from standard classes field", classes.size());
                    }
                } catch (Exception e2) {
                    EuphoriaCompanion.LOGGER.warn("Could not access standard classes field: {}", e2.getMessage());
                }
            }

            // Also check parent classloaders
            ClassLoader parent = classLoader.getParent();
            while (parent != null) {
                try {
                    java.lang.reflect.Field classesField = ClassLoader.class.getDeclaredField("classes");
                    classesField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Vector<Class<?>> parentClasses = (Vector<Class<?>>) classesField.get(parent);
                    if (parentClasses != null) {
                        allClasses.addAll(parentClasses);
                        EuphoriaCompanion.LOGGER.info("Retrieved {} classes from parent classloader {}", parentClasses.size(), parent.getClass().getName());
                    }
                } catch (Exception e) {
                    // Some classloaders might not have this field, skip
                }
                parent = parent.getParent();
            }

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Error retrieving loaded classes", e);
        }

        return allClasses;
    }

    /**
     * Checks if a class is an Entity class (non-abstract, extends Entity)
     */
    private static boolean isEntityClass(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }

        // Must extend Entity
        if (!Entity.class.isAssignableFrom(clazz)) {
            return false;
        }

        // Don't include the Entity class itself
        if (clazz == Entity.class) {
            return false;
        }

        // Exclude EntityFX (particle effects) - they're client-side only and don't need registration
        try {
            Class<?> entityFXClass = Class.forName("net.minecraft.client.particle.EntityFX");
            if (entityFXClass.isAssignableFrom(clazz)) {
                return false;
            }
        } catch (ClassNotFoundException e) {
            // EntityFX not available (shouldn't happen in normal Minecraft), continue
        }

        // Include both abstract and concrete classes for completeness
        return true;
    }

    /**
     * Filters to only unregistered entities
     */
    private static Map<String, EntityInfo> filterUnregisteredEntities(
            Set<Class<?>> allClasses,
            Map<Class<?>, String> registeredEntities,
            boolean quoteIds) {

        Map<String, EntityInfo> unregisteredByClass = new TreeMap<>();

        for (Class<?> clazz : allClasses) {
            String className = clazz.getName();
            String registryName = registeredEntities.get(clazz);
            boolean isRegistered = registryName != null;

            // Skip registered entities
            if (isRegistered) {
                continue;
            }

            boolean isAbstract = Modifier.isAbstract(clazz.getModifiers());
            boolean isInterface = clazz.isInterface();

            String modNamespace = extractModNamespace(className, registryName);

            EntityInfo info = new EntityInfo(
                    className,
                    registryName,
                    isRegistered,
                    isAbstract,
                    isInterface,
                    modNamespace
            );

            unregisteredByClass.put(className, info);
        }

        return unregisteredByClass;
    }

    /**
     * Extracts mod namespace from class name or registry name
     */
    private static String extractModNamespace(String className, String registryName) {
        // Try registry name first
        if (registryName != null && registryName.contains(".")) {
            return registryName.split("\\.", 2)[0];
        }

        // Fall back to package name heuristics
        if (className.startsWith("net.minecraft.")) {
            return "minecraft";
        }

        // Try to extract from package name
        String[] parts = className.split("\\.");
        if (parts.length > 0) {
            return parts[0];
        }

        return "unknown";
    }

    /**
     * Writes the unregistered entity report
     */
    private static void writeReport(Path tempPath, Map<String, EntityInfo> entitiesByClass, boolean quoteIds) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(tempPath)) {
            writer.write("=== UNREGISTERED ENTITY SCAN ===\n");
            writer.write("Shows only unregistered Entity classes found on classpath.\n");
            writer.write("Registered entities are excluded from this report.\n\n");

            // Group by namespace
            Map<String, List<EntityInfo>> byNamespace = new TreeMap<>();
            for (EntityInfo info : entitiesByClass.values()) {
                byNamespace.computeIfAbsent(info.modNamespace, k -> new ArrayList<>()).add(info);
            }

            int totalUnregistered = 0;
            int totalAbstract = 0;

            // Write by namespace
            for (Map.Entry<String, List<EntityInfo>> entry : byNamespace.entrySet()) {
                String namespace = entry.getKey();
                List<EntityInfo> entities = entry.getValue();

                // Sort by class name
                entities.sort(Comparator.comparing(e -> e.className));

                // Count categories
                long unregistered = entities.stream().filter(e -> !e.isAbstract && !e.isInterface).count();
                long abstractCount = entities.stream().filter(e -> e.isAbstract || e.isInterface).count();

                totalUnregistered += unregistered;
                totalAbstract += abstractCount;

                writer.write("========================================\n");
                writer.write(namespace + " (" + entities.size() + " unregistered entity classes)\n");
                writer.write("  Unregistered (concrete): " + unregistered + "\n");
                writer.write("  Abstract/Interface: " + abstractCount + "\n\n");

                // Write unregistered concrete entities
                writer.write("  UNREGISTERED ENTITIES (Concrete):\n");
                boolean foundUnregistered = false;
                for (EntityInfo info : entities) {
                    if (!info.isAbstract && !info.isInterface) {
                        writer.write("    [U] " + info.className + "\n");
                        foundUnregistered = true;
                    }
                }
                if (!foundUnregistered) {
                    writer.write("    (none)\n");
                }

                // Write abstract/interface entities
                writer.write("\n  ABSTRACT/INTERFACE:\n");
                boolean foundAbstract = false;
                for (EntityInfo info : entities) {
                    if (info.isAbstract || info.isInterface) {
                        String type = info.isInterface ? "Interface" : "Abstract";
                        writer.write("    [A] " + info.className + " (" + type + ")\n");
                        foundAbstract = true;
                    }
                }
                if (!foundAbstract) {
                    writer.write("    (none)\n");
                }

                writer.write("\n");
            }

            writer.write("========================================\n");
            writer.write("SUMMARY:\n");
            writer.write("  Total Unregistered (Concrete): " + totalUnregistered + "\n");
            writer.write("  Total Abstract/Interface: " + totalAbstract + "\n");
            writer.write("  TOTAL UNREGISTERED CLASSES: " + entitiesByClass.size() + "\n");
        }
    }

    /**
     * Entity information container
     */
    private static class EntityInfo {
        final String className;
        final String registryName;
        final boolean isRegistered;
        final boolean isAbstract;
        final boolean isInterface;
        final String modNamespace;

        EntityInfo(String className, String registryName, boolean isRegistered,
                   boolean isAbstract, boolean isInterface, String modNamespace) {
            this.className = className;
            this.registryName = registryName;
            this.isRegistered = isRegistered;
            this.isAbstract = isAbstract;
            this.isInterface = isInterface;
            this.modNamespace = modNamespace;
        }
    }
}
