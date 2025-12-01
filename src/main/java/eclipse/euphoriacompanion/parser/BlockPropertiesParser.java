package eclipse.euphoriacompanion.parser;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses block.properties files with support for conditional directives,
 * tag definitions, and property assignments.
 */
public class BlockPropertiesParser {
    private static final Pattern IF_PATTERN = Pattern.compile("#if\\s+(\\w+)\\s*([!=<>]+)\\s*(\\d+)");
    private static final Pattern DEFINE_PATTERN = Pattern.compile("#define\\s+(\\w+)\\s+(.+)");

    // Parsed data
    private final Map<String, Integer> blockToProperty = new HashMap<>();
    private final Map<String, String> blockToRenderLayer = new HashMap<>();
    private final Map<String, String> tagDefinitions = new LinkedHashMap<>();  // Preserve insertion order for first-assignment-wins
    private final Map<String, Integer> tagToProperty = new LinkedHashMap<>();  // Preserve insertion order for first-assignment-wins
    private final Map<String, List<Integer>> duplicateBlocks = new HashMap<>();
    private final ModConfig config;
    private final int currentMCVersion;
    private boolean irisLoaded = false;
    private final boolean oculusLoaded;
    private final int oculusVersion;
    private final int irisTagSupport;

    public BlockPropertiesParser(ModConfig config, int currentMCVersion) {
        this.config = config;
        this.currentMCVersion = currentMCVersion;
        boolean euphoriaPatchesEnabled = config.detectEuphoriaPatchesSupport();

        // IRIS_TAG_SUPPORT variable (0 = disabled, 2 = enabled for Iris 1.8+)
        this.irisTagSupport = config.isTagSupportEnabled() ? 2 : 0;

        // Euphoria Companion defines (only available with Euphoria Patches 1.7.8+)
        if (euphoriaPatchesEnabled) {
            this.oculusLoaded = FabricLoader.getInstance().isModLoaded("oculus");
            if (!oculusLoaded) {
                this.irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
            }
            this.oculusVersion = getOculusVersionInt();
            EuphoriaCompanion.LOGGER.info("Euphoria Companion defines: EUPHORIA_PATCHES_IRIS={}, EUPHORIA_PATCHES_OCULUS={}, EUPHORIA_PATCHES_OCULUS_VERSION={}",
                irisLoaded, oculusLoaded, oculusVersion);
        } else {
            this.irisLoaded = false;
            this.oculusLoaded = false;
            this.oculusVersion = 0;
            EuphoriaCompanion.LOGGER.info("Euphoria Patches not detected, Euphoria Companion defines disabled");
        }

        EuphoriaCompanion.LOGGER.info("IRIS_TAG_SUPPORT = {}", irisTagSupport);
    }

    /**
     * Gets Oculus version as integer (for EUPHORIA_PATCHES_OCULUS_VERSION define)
     * Returns version in format: major*10000 + minor*100 + patch
     * e.g., 1.7.0 -> 10700
     */
    private int getOculusVersionInt() {
        return FabricLoader.getInstance().getModContainer("oculus")
            .map(modContainer -> {
                String version = modContainer.getMetadata().getVersion().getFriendlyString();

                // Parse version string (e.g., "1.7.0", "1.7.0+mc1.21")
                try {
                    String[] parts = version.split("[.+]");
                    if (parts.length >= 3) {
                        int major = Integer.parseInt(parts[0]);
                        int minor = Integer.parseInt(parts[1]);
                        int patch = Integer.parseInt(parts[2]);

                        return major * 10000 + minor * 100 + patch;
                    }
                } catch (NumberFormatException e) {
                    EuphoriaCompanion.LOGGER.warn("Failed to parse Oculus version: {}", version);
                }

                return 0;
            })
            .orElse(0);
    }

    /**
     * Parses a block.properties file
     */
    public void parse(Path propertiesFile) throws IOException {
        Deque<ConditionalContext> conditionalStack = new ArrayDeque<>();

        try (BufferedReader reader = Files.newBufferedReader(propertiesFile)) {
            String line;
            int lineNumber = 0;

            try {

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Handle line continuation (backslash at end)
                if (line.endsWith("\\")) {
                    StringBuilder continuedLine = new StringBuilder();

                    // Keep reading and concatenating lines until we find one without trailing backslash
                    while (line.endsWith("\\")) {
                        // Remove the trailing backslash
                        String withoutBackslash = line.substring(0, line.length() - 1).trim();

                        // Only append non-empty content (skip lines that are just "\")
                        if (!withoutBackslash.isEmpty()) {
                            if (!continuedLine.isEmpty()) {
                                continuedLine.append(" ");
                            }
                            continuedLine.append(withoutBackslash);
                        }

                        // Read next line
                        line = reader.readLine();
                        if (line == null) {
                            break; // End of file
                        }
                        lineNumber++;
                        line = line.trim();
                    }

                    // Append the final line (without backslash)
                    if (line != null && !line.isEmpty()) {
                        if (!continuedLine.isEmpty()) {
                            continuedLine.append(" ");
                        }
                        continuedLine.append(line);
                    }

                    // Use the merged line for processing
                    line = continuedLine.toString();
                    // Keep lineNumber at current position (end of continuation), not startLine
                }

                // Handle conditional directives (ALWAYS process these, even in inactive blocks)
                if (line.startsWith("#ifdef ") || line.startsWith("#ifndef ")) {
                    handleIfdefDirective(line, conditionalStack, lineNumber);
                    continue;
                } else if (line.startsWith("#if ")) {
                    handleIfDirective(line, conditionalStack, lineNumber);
                    continue;
                } else if (line.startsWith("#else")) {
                    handleElseDirective(conditionalStack, lineNumber);
                    continue;
                } else if (line.startsWith("#endif")) {
                    handleEndifDirective(conditionalStack, lineNumber);
                    continue;
                }

                // Skip ALL other processing if inside an inactive conditional block
                if (!isActiveContext(conditionalStack)) {
                    continue;
                }

                // Skip empty lines and comments
                if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("#define"))) {
                    continue;
                }

                // Handle #define statements (tag definitions)
                if (line.startsWith("#define") && config.isTagSupportEnabled()) {
                    handleDefineDirective(line, lineNumber);
                    continue;
                }

                // Handle property assignments
                if (line.contains("=")) {
                    handlePropertyAssignment(line, lineNumber);
                }
            }
            } catch (IOException e) {
                throw new IOException("Error reading block.properties at line " + lineNumber, e);
            }
        }

        // Check for unmatched #if directives
        if (!conditionalStack.isEmpty()) {
            EuphoriaCompanion.LOGGER.warn("Parsing ended with {} unmatched #if directive(s)", conditionalStack.size());
        }

        EuphoriaCompanion.LOGGER.info("Parsed {} direct block assignments and {} tag definitions",
            blockToProperty.size(), tagDefinitions.size());
    }

    /**
     * Handles #if conditional directives
     */
    private void handleIfDirective(String line, Deque<ConditionalContext> stack, int lineNumber) {
        EuphoriaCompanion.LOGGER.debug("Line {}: #if [{}] (stack depth before: {})", lineNumber, line, stack.size());

        // Extract the expression after "#if "
        String expression = line.substring(4).trim();

        // Check if we're already in an inactive context
        boolean parentActive = isActiveContext(stack);

        // Try to evaluate the expression
        Boolean result = evaluateExpression(expression);

        if (result != null) {
            // Successfully evaluated
            boolean active = parentActive && result;
            stack.push(new ConditionalContext(true, active));
            EuphoriaCompanion.LOGGER.debug("Line {}: #if evaluated to {} -> {} (stack depth after: {})",
                lineNumber, result, active, stack.size());
        } else {
            // Could not parse/evaluate - mark as unsupported
            EuphoriaCompanion.LOGGER.warn("Line {}: Unsupported #if expression: {} (stack depth: {})",
                lineNumber, expression, stack.size());
            stack.push(new ConditionalContext(false, false));
        }
    }

    /**
     * Evaluates a complex conditional expression
     * Supports: defined SYMBOL, variable comparisons, && and || operators
     */
    private Boolean evaluateExpression(String expression) {
        try {
            EuphoriaCompanion.LOGGER.debug("Evaluating expression: [{}]", expression);

            // Handle OR (||) - lowest precedence
            if (expression.contains("||")) {
                String[] orParts = expression.split("\\|\\|");
                EuphoriaCompanion.LOGGER.debug("Split on OR, {} parts", orParts.length);
                for (String part : orParts) {
                    EuphoriaCompanion.LOGGER.debug("Evaluating OR part: [{}]", part.trim());
                    Boolean result = evaluateExpression(part.trim());
                    EuphoriaCompanion.LOGGER.debug("OR part result: {}", result);
                    if (result == null) return null; // Can't evaluate
                    if (result) return true; // Short-circuit OR
                }
                return false;
            }

            // Handle AND (&&) - higher precedence
            if (expression.contains("&&")) {
                String[] andParts = expression.split("&&");
                EuphoriaCompanion.LOGGER.debug("Split on AND, {} parts", andParts.length);
                for (String part : andParts) {
                    EuphoriaCompanion.LOGGER.debug("Evaluating AND part: [{}]", part.trim());
                    Boolean result = evaluateExpression(part.trim());
                    EuphoriaCompanion.LOGGER.debug("AND part result: {}", result);
                    if (result == null) return null; // Can't evaluate
                    if (!result) return false; // Short-circuit AND
                }
                return true;
            }

            // Handle "defined SYMBOL"
            if (expression.startsWith("defined ")) {
                String symbol = expression.substring(8).trim();
                boolean defined = isSymbolDefined(symbol);
                EuphoriaCompanion.LOGGER.debug("Checking defined {}: {}", symbol, defined);
                return defined;
            }

            // Handle simple comparisons (MC_VERSION >= 12100, etc.)
            Matcher matcher = IF_PATTERN.matcher("#if " + expression);
            if (matcher.matches()) {
                String variable = matcher.group(1);
                String operator = matcher.group(2);
                int value = Integer.parseInt(matcher.group(3));

                switch (variable) {
                    case "MC_VERSION" -> {
                        boolean result = evaluateCondition(currentMCVersion, operator, value);
                        EuphoriaCompanion.LOGGER.debug("MC_VERSION {} {} -> {}", operator, value, result);
                        return result;
                    }
                    case "EUPHORIA_PATCHES_OCULUS_VERSION" -> {
                        boolean result = evaluateCondition(oculusVersion, operator, value);
                        EuphoriaCompanion.LOGGER.debug("EUPHORIA_PATCHES_OCULUS_VERSION {} {} -> {}", operator, value, result);
                        return result;
                    }
                    case "IRIS_TAG_SUPPORT" -> {
                        boolean result = evaluateCondition(irisTagSupport, operator, value);
                        EuphoriaCompanion.LOGGER.debug("IRIS_TAG_SUPPORT ({}) {} {} -> {}", irisTagSupport, operator, value, result);
                        return result;
                    }
                }
            }

            // Couldn't parse this expression
            EuphoriaCompanion.LOGGER.debug("Could not parse expression: [{}]", expression);
            return null;

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.warn("Error evaluating expression: {}", expression, e);
            return null;
        }
    }

    /**
     * Checks if a symbol is defined
     */
    private boolean isSymbolDefined(String symbol) {
        return switch (symbol) {
            case "EUPHORIA_PATCHES_IRIS" -> irisLoaded;
            case "EUPHORIA_PATCHES_OCULUS" -> oculusLoaded;
            default -> false; // Unknown symbols are not defined
        };
    }

    /**
     * Handles #ifdef and #ifndef conditional directives
     */
    private void handleIfdefDirective(String line, Deque<ConditionalContext> stack, int lineNumber) {
        boolean isIfndef = line.startsWith("#ifndef");
        String directiveName = isIfndef ? "#ifndef" : "#ifdef";

        EuphoriaCompanion.LOGGER.debug("Line {}: {} [{}] (stack depth before: {})", lineNumber, directiveName, line, stack.size());

        // Extract the symbol name after #ifdef/#ifndef
        String symbol = line.substring(isIfndef ? 8 : 7).trim();

        // Check if we're already in an inactive context
        boolean parentActive = isActiveContext(stack);

        // Check for Euphoria Companion defines (only available with Euphoria Patches 1.7.8+)
        boolean symbolDefined = false;
        boolean supported = false;

        if (symbol.equals("EUPHORIA_PATCHES_IRIS")) {
            symbolDefined = irisLoaded;
            supported = true;
            EuphoriaCompanion.LOGGER.debug("Line {}: Checking EUPHORIA_PATCHES_IRIS -> {}", lineNumber, symbolDefined);
        } else if (symbol.equals("EUPHORIA_PATCHES_OCULUS")) {
            symbolDefined = oculusLoaded;
            supported = true;
            EuphoriaCompanion.LOGGER.debug("Line {}: Checking EUPHORIA_PATCHES_OCULUS -> {}", lineNumber, symbolDefined);
        }

        // #ifdef: active if symbol IS defined
        // #ifndef: active if symbol IS NOT defined
        boolean condition = isIfndef != symbolDefined;
        boolean active = parentActive && condition;

        stack.push(new ConditionalContext(supported, active));

        EuphoriaCompanion.LOGGER.debug("Line {}: {} {} -> {} (stack depth after: {})",
            lineNumber, directiveName, symbol, active, stack.size());
    }

    /**
     * Handles #else directives
     */
    private void handleElseDirective(Deque<ConditionalContext> stack, int lineNumber) {
        EuphoriaCompanion.LOGGER.debug("Line {}: #else (stack depth before: {})", lineNumber, stack.size());

        if (stack.isEmpty()) {
            EuphoriaCompanion.LOGGER.warn("Line {}: #else without matching #if (stack is empty)", lineNumber);
            return;
        }

        // Pop the current context and invert its condition
        ConditionalContext current = stack.pop();

        // Check if parent context is active
        boolean parentActive = isActiveContext(stack);

        // #else logic:
        // - If the #if was supported and evaluated: flip the condition
        // - If the #if was unsupported (couldn't parse): activate #else as fallback
        boolean elseActive;
        if (current.supported()) {
            // Supported #if: activate #else only if #if was false
            elseActive = parentActive && !current.active();
        } else {
            // Unsupported #if: activate #else as fallback (assume we want the #else block)
            elseActive = parentActive;
        }

        stack.push(new ConditionalContext(current.supported(), elseActive));

        EuphoriaCompanion.LOGGER.debug("Line {}: #else -> {} (supported: {}, stack depth after: {})",
            lineNumber, elseActive, current.supported(), stack.size());
    }

    /**
     * Handles #endif directives
     */
    private void handleEndifDirective(Deque<ConditionalContext> stack, int lineNumber) {
        EuphoriaCompanion.LOGGER.debug("Line {}: #endif (stack depth before: {})", lineNumber, stack.size());

        if (!stack.isEmpty()) {
            stack.pop();
            EuphoriaCompanion.LOGGER.debug("Line {}: #endif processed (stack depth after: {})", lineNumber, stack.size());
        } else {
            EuphoriaCompanion.LOGGER.warn("Line {}: #endif without matching #if (stack is empty)", lineNumber);
        }
    }

    /**
     * Handles #define directives for tag definitions
     */
    private void handleDefineDirective(String line, int lineNumber) {
        Matcher matcher = DEFINE_PATTERN.matcher(line);
        if (matcher.matches()) {
            String identifier = matcher.group(1);
            String tagName = matcher.group(2);
            tagDefinitions.put(identifier, tagName);
            EuphoriaCompanion.LOGGER.debug("Line {}: Defined tag {} = %{}", lineNumber, identifier, tagName);
        } else {
            EuphoriaCompanion.LOGGER.warn("Line {}: Invalid #define directive: {}", lineNumber, line);
        }
    }

    /**
     * Handles property assignments (block.XX=..., layer.XX=..., etc.)
     */
    private void handlePropertyAssignment(String line, int lineNumber) {
        String[] parts = line.split("=", 2);
        if (parts.length != 2) {
            return;
        }

        String key = parts[0].trim();
        String value = parts[1].trim();

        // Handle block property assignments (block.XX=...)
        if (key.startsWith("block.")) {
            handleBlockProperty(key, value, lineNumber);
        }
        // Handle render layer assignments (layer.translucent=...)
        else if (key.startsWith("layer.")) {
            handleRenderLayer(key, value);
        }
    }

    /**
     * Handles block property assignments
     */
    private void handleBlockProperty(String key, String value, int lineNumber) {
        // Extract property ID from "block.XX"
        String propertyIdStr = key.substring(6); // Remove "block." prefix
        int propertyId;
        try {
            propertyId = Integer.parseInt(propertyIdStr);
        } catch (NumberFormatException e) {
            EuphoriaCompanion.LOGGER.warn("Line {}: Invalid property ID: {}", lineNumber, propertyIdStr);
            return;
        }

        // Parse block IDs from value
        String[] blockIds = value.split("\\s+");
        for (String blockId : blockIds) {
            blockId = blockId.trim();
            if (blockId.isEmpty()) {
                continue;
            }

            // Check if this is a tag reference
            if (tagDefinitions.containsKey(blockId)) {
                // Tag-based assignment
                tagToProperty.put(blockId, propertyId);
                EuphoriaCompanion.LOGGER.debug("Line {}: Tag {} -> property {}", lineNumber, blockId, propertyId);
            } else {
                // Direct block assignment
                String normalizedId = normalizeBlockId(blockId);
                if (normalizedId == null) {
                    continue; // Skip invalid block ID
                }

                // Check for duplicates
                if (blockToProperty.containsKey(normalizedId)) {
                    int existingProperty = blockToProperty.get(normalizedId);

                    // Track this as a duplicate
                    duplicateBlocks.computeIfAbsent(normalizedId, k -> new ArrayList<>());
                    List<Integer> properties = duplicateBlocks.get(normalizedId);

                    // Add the existing property if not already in the list
                    if (!properties.contains(existingProperty)) {
                        properties.add(existingProperty);
                    }

                    // Add the new property
                    if (!properties.contains(propertyId)) {
                        properties.add(propertyId);
                    }

                    EuphoriaCompanion.LOGGER.debug("Line {}: Duplicate block {} already mapped to block.{}, now also to block.{}",
                        lineNumber, normalizedId, existingProperty, propertyId);
                }

                blockToProperty.put(normalizedId, propertyId);
            }
        }
    }

    /**
     * Handles render layer assignments
     */
    private void handleRenderLayer(String key, String value) {
        // Extract layer name from "layer.XX"
        String layerName = key.substring(6); // Remove "layer." prefix

        // Parse block IDs from value
        String[] blockIds = value.split("\\s+");
        for (String blockId : blockIds) {
            blockId = blockId.trim();
            if (blockId.isEmpty()) {
                continue;
            }

            String normalizedId = normalizeBlockId(blockId);
            if (normalizedId != null) {
                blockToRenderLayer.put(normalizedId, layerName);
            }
        }
    }

    /**
     * Normalizes a block ID (adds minecraft: namespace if missing)
     * Handles: "cobweb", "furnace:lit=true", "minecraft:stone", "create:andesite_casing:waterlogged=true"
     */
    private String normalizeBlockId(String blockId) {
        // Validate input
        if (blockId == null || blockId.trim().isEmpty()) {
            EuphoriaCompanion.LOGGER.warn("Empty or null block ID provided");
            return null;
        }

        String trimmed = blockId.trim();

        // Check for invalid cases
        if (trimmed.startsWith(":") || trimmed.endsWith(":")) {
            EuphoriaCompanion.LOGGER.warn("Invalid block ID format: {}", blockId);
            return null;
        }

        // Split by colon to analyze structure
        String[] parts = trimmed.split(":");

        if (parts.length == 1) {
            // No colon: "cobweb" -> "minecraft:cobweb"
            return "minecraft:" + trimmed;
        }

        // Check if second part contains '=' (indicates blockstate)
        if (parts[1].contains("=")) {
            // Format: "furnace:lit=true" (vanilla block with state, no namespace)
            return "minecraft:" + trimmed;
        }

        // Check if second part contains another ':' or if there are more than 2 parts
        // Format: "namespace:blockname:property=value..."
        // Already has namespace, return as-is

        // Two parts, second doesn't contain '=': "namespace:blockname" or "minecraft:stone"
        // Already has namespace, return as-is
        return trimmed;
    }

    /**
     * Checks if the current context is active
     */
    private boolean isActiveContext(Deque<ConditionalContext> stack) {
        for (ConditionalContext context : stack) {
            if (!context.active) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a conditional expression
     */
    private boolean evaluateCondition(int left, String operator, int right) {
        return switch (operator) {
            case "==" -> left == right;
            case "!=" -> left != right;
            case "<" -> left < right;
            case ">" -> left > right;
            case "<=" -> left <= right;
            case ">=" -> left >= right;
            default -> {
                EuphoriaCompanion.LOGGER.warn("Unknown operator: {}", operator);
                yield false;
            }
        };
    }

    // Getters for parsed data
    public Map<String, Integer> getBlockToProperty() {
        return Collections.unmodifiableMap(blockToProperty);
    }

    public Map<String, String> getBlockToRenderLayer() {
        return Collections.unmodifiableMap(blockToRenderLayer);
    }

    public Map<String, String> getTagDefinitions() {
        return Collections.unmodifiableMap(tagDefinitions);
    }

    public Map<String, Integer> getTagToProperty() {
        return Collections.unmodifiableMap(tagToProperty);
    }

    public Map<String, List<Integer>> getDuplicateBlocks() {
        return Collections.unmodifiableMap(duplicateBlocks);
    }

    /**
     * Represents a conditional context in the stack
     */
    private record ConditionalContext(boolean supported, boolean active) {
    }
}
