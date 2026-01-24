package eclipse.euphoriacompanion.parser;

import cpw.mods.fml.common.Loader;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.config.ModConfig;
import eclipse.euphoriacompanion.util.BlockIdFormatter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlockPropertiesParser {
    private static final Pattern IF_PATTERN = Pattern.compile("#if\\s+(\\w+)\\s*([!=<>]+)\\s*(\\d+)");

    private final Map<String, Integer> blockToProperty = new HashMap<>();
    private final Map<String, List<Integer>> duplicateBlocks = new HashMap<>();
    private final int currentMCVersion;
    private final boolean angelicaLoaded;

    public BlockPropertiesParser(ModConfig config, int currentMCVersion) {
        this.currentMCVersion = currentMCVersion;
        boolean euphoriaPatcherEnabled = config.detectEuphoriaPatcherSupport();

        // Requires Euphoria Patcher 1.7.8+ to expose defines to shaderpack authors
        if (euphoriaPatcherEnabled) {
            this.angelicaLoaded = Loader.isModLoaded("angelica");
            EuphoriaCompanion.LOGGER.info("Euphoria Companion defines: EUPHORIA_PATCHES_ANGELICA={}", angelicaLoaded);
        } else {
            this.angelicaLoaded = false;
            EuphoriaCompanion.LOGGER.info("Euphoria Patcher not detected, Euphoria Companion defines disabled");
        }
    }

    public void parse(Path propertiesFile) throws IOException {
        Deque<ConditionalContext> conditionalStack = new ArrayDeque<>();

        try (BufferedReader reader = Files.newBufferedReader(propertiesFile)) {
            String line;
            int lineNumber = 0;

            try {

                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();

                    if (line.endsWith("\\")) {
                        StringBuilder continuedLine = new StringBuilder();

                        while (line.endsWith("\\")) {
                            String withoutBackslash = line.substring(0, line.length() - 1).trim();

                            if (!withoutBackslash.isEmpty()) {
                                if (continuedLine.length() > 0) {
                                    continuedLine.append(" ");
                                }
                                continuedLine.append(withoutBackslash);
                            }

                            line = reader.readLine();
                            if (line == null) {
                                break;
                            }
                            lineNumber++;
                            line = line.trim();
                        }

                        if (line != null && !line.isEmpty()) {
                            if (continuedLine.length() > 0) {
                                continuedLine.append(" ");
                            }
                            continuedLine.append(line);
                        }

                        line = continuedLine.toString();
                    }

                    // Process conditionals even in inactive blocks to maintain stack depth
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

                    if (!isActiveContext(conditionalStack)) {
                        continue;
                    }

                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    if (line.contains("=")) {
                        handlePropertyAssignment(line, lineNumber);
                    }
                }
            } catch (IOException e) {
                throw new IOException("Error reading block.properties at line " + lineNumber, e);
            }
        }

        if (!conditionalStack.isEmpty()) {
            EuphoriaCompanion.LOGGER.warn("Parsing ended with {} unmatched #if directive(s)", conditionalStack.size());
        }

        EuphoriaCompanion.LOGGER.info("Parsed {} direct block assignments", blockToProperty.size());
    }

    /**
     * Handles #if conditional directives
     */
    private void handleIfDirective(String line, Deque<ConditionalContext> stack, int lineNumber) {
        EuphoriaCompanion.LOGGER.debug("Line {}: #if [{}] (stack depth before: {})", lineNumber, line, stack.size());

        // Extract the expression after "#if "
        String expression = line.substring(4).trim();

        boolean parentActive = isActiveContext(stack);

        Boolean result = evaluateExpression(expression);

        if (result != null) {
            boolean active = parentActive && result;
            stack.push(new ConditionalContext(true, active));
            EuphoriaCompanion.LOGGER.debug("Line {}: #if evaluated to {} -> {} (stack depth after: {})",
                    lineNumber, result, active, stack.size());
        } else {
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

            if (expression.contains("||")) {
                String[] orParts = expression.split("\\|\\|");
                EuphoriaCompanion.LOGGER.debug("Split on OR, {} parts", orParts.length);
                for (String part : orParts) {
                    EuphoriaCompanion.LOGGER.debug("Evaluating OR part: [{}]", part.trim());
                    Boolean result = evaluateExpression(part.trim());
                    EuphoriaCompanion.LOGGER.debug("OR part result: {}", result);
                    if (result == null) return null;
                    if (result) return true;
                }
                return false;
            }

            if (expression.contains("&&")) {
                String[] andParts = expression.split("&&");
                EuphoriaCompanion.LOGGER.debug("Split on AND, {} parts", andParts.length);
                for (String part : andParts) {
                    EuphoriaCompanion.LOGGER.debug("Evaluating AND part: [{}]", part.trim());
                    Boolean result = evaluateExpression(part.trim());
                    EuphoriaCompanion.LOGGER.debug("AND part result: {}", result);
                    if (result == null) return null;
                    if (!result) return false;
                }
                return true;
            }

            if (expression.startsWith("defined ")) {
                String symbol = expression.substring(8).trim();
                boolean defined = isSymbolDefined(symbol);
                EuphoriaCompanion.LOGGER.debug("Checking defined {}: {}", symbol, defined);
                return defined;
            }

            Matcher matcher = IF_PATTERN.matcher("#if " + expression);
            if (matcher.matches()) {
                String variable = matcher.group(1);
                String operator = matcher.group(2);
                int value = Integer.parseInt(matcher.group(3));

                if (variable.equals("MC_VERSION")) {
                    boolean result = evaluateCondition(currentMCVersion, operator, value);
                    EuphoriaCompanion.LOGGER.debug("MC_VERSION {} {} -> {}", operator, value, result);
                    return result;
                }
            }

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
        if (symbol.equals("EUPHORIA_PATCHES_ANGELICA")) {
            return angelicaLoaded;
        } else {
            return false;
        }
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

        boolean parentActive = isActiveContext(stack);

        boolean symbolDefined = false;
        boolean supported = false;

        if (symbol.equals("EUPHORIA_PATCHES_ANGELICA")) {
            symbolDefined = angelicaLoaded;
            supported = true;
            EuphoriaCompanion.LOGGER.debug("Line {}: Checking EUPHORIA_PATCHES_ANGELICA -> {}", lineNumber, symbolDefined);
        }

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
        boolean elseActive = isElseActive(stack, current);

        stack.push(new ConditionalContext(current.isSupported(), elseActive));

        EuphoriaCompanion.LOGGER.debug("Line {}: #else -> {} (supported: {}, stack depth after: {})",
                lineNumber, elseActive, current.isSupported(), stack.size());
    }

    private boolean isElseActive(Deque<ConditionalContext> stack, ConditionalContext current) {
        boolean parentActive = isActiveContext(stack);

        boolean elseActive;
        if (current.isSupported()) {
            elseActive = parentActive && !current.isActive();
        } else {
            elseActive = parentActive;
        }
        return elseActive;
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
     * Handles property assignments (block.XX=...)
     */
    private void handlePropertyAssignment(String line, int lineNumber) {
        String[] parts = line.split("=", 2);
        if (parts.length != 2) {
            return;
        }

        String key = parts[0].trim();
        String value = parts[1].trim();

        if (key.startsWith("block.")) {
            handleBlockProperty(key, value, lineNumber);
        }
    }

    /**
     * Handles block property assignments
     */
    private void handleBlockProperty(String key, String value, int lineNumber) {
        // Extract property ID from "block.XX"
        String propertyIdStr = key.substring(6);
        int propertyId;
        try {
            propertyId = Integer.parseInt(propertyIdStr);
        } catch (NumberFormatException e) {
            EuphoriaCompanion.LOGGER.warn("Line {}: Invalid property ID: {}", lineNumber, propertyIdStr);
            return;
        }

        // Parse block IDs from value (respecting quoted strings)
        List<String> blockIds = parseBlockIds(value);
        for (String blockId : blockIds) {
            blockId = blockId.trim();
            if (blockId.isEmpty()) {
                continue;
            }

            // Unescape quoted block IDs
            blockId = BlockIdFormatter.unformatId(blockId);

            // Expand comma-separated metadata values (e.g., "stone:0,1,2" -> ["stone:0", "stone:1", "stone:2"])
            List<String> expandedBlockIds = expandCommaSeparatedMetadata(blockId);

            for (String expandedId : expandedBlockIds) {
                String normalizedId = normalizeBlockId(expandedId);
                if (normalizedId == null) {
                    continue;
                }

                if (blockToProperty.containsKey(normalizedId)) {
                    int existingProperty = blockToProperty.get(normalizedId);

                    if (!duplicateBlocks.containsKey(normalizedId)) {
                        duplicateBlocks.put(normalizedId, new ArrayList<>());
                    }
                    List<Integer> properties = duplicateBlocks.get(normalizedId);

                    if (!properties.contains(existingProperty)) {
                        properties.add(existingProperty);
                    }

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
     * Parses block IDs from a value string, respecting quoted strings.
     * Examples:
     * "stone cobblestone dirt" -> ["stone", "cobblestone", "dirt"]
     * "\"mod:block name\" stone" -> ["\"mod:block name\"", "stone"]
     * "\"mod:path\\\\to\\\\block\" dirt" -> ["\"mod:path\\\\to\\\\block\"", "dirt"]
     */
    private List<String> parseBlockIds(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }

            if (c == '"') {
                current.append(c);
                inQuotes = !inQuotes;
                continue;
            }

            if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    /**
     * Expands comma-separated metadata values in a block ID
     * Examples:
     * "minecraft:stone:0,1,2" -> ["minecraft:stone:0", "minecraft:stone:1", "minecraft:stone:2"]
     * "stone:0,1,2" -> ["stone:0", "stone:1", "stone:2"]
     * "stone" -> ["stone"] (no metadata, return as-is)
     */
    private List<String> expandCommaSeparatedMetadata(String blockId) {
        List<String> result = new ArrayList<>();

        int lastColonIndex = blockId.lastIndexOf(':');

        if (lastColonIndex == -1) {
            result.add(blockId);
            return result;
        }

        String afterLastColon = blockId.substring(lastColonIndex + 1);

        if (!afterLastColon.contains(",")) {
            result.add(blockId);
            return result;
        }

        if (!afterLastColon.matches("[0-9,]+")) {
            result.add(blockId);
            return result;
        }

        String[] metadataValues = afterLastColon.split(",");
        String baseBlockId = blockId.substring(0, lastColonIndex);

        for (String metadata : metadataValues) {
            metadata = metadata.trim();
            if (!metadata.isEmpty()) {
                try {
                    int metaInt = Integer.parseInt(metadata);
                    if (metaInt >= 0 && metaInt <= 15) {
                        result.add(baseBlockId + ":" + metaInt);
                    } else {
                        EuphoriaCompanion.LOGGER.warn("Invalid metadata value (must be 0-15): {}", metadata);
                    }
                } catch (NumberFormatException e) {
                    EuphoriaCompanion.LOGGER.warn("Invalid metadata value: {}", metadata);
                }
            }
        }

        if (result.isEmpty()) {
            result.add(blockId);
        }

        return result;
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
            if (!context.isActive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a conditional expression
     */
    private boolean evaluateCondition(int left, String operator, int right) {
        switch (operator) {
            case "==":
                return left == right;
            case "!=":
                return left != right;
            case "<":
                return left < right;
            case ">":
                return left > right;
            case "<=":
                return left <= right;
            case ">=":
                return left >= right;
            default:
                EuphoriaCompanion.LOGGER.warn("Unknown operator: {}", operator);
                return false;
        }
    }

    // Getters for parsed data
    public Map<String, Integer> getBlockToProperty() {
        return Collections.unmodifiableMap(blockToProperty);
    }

    public Map<String, List<Integer>> getDuplicateBlocks() {
        return Collections.unmodifiableMap(duplicateBlocks);
    }

    /**
     * Represents a conditional context in the stack
     */
    private static class ConditionalContext {
        private final boolean supported;
        private final boolean active;

        public ConditionalContext(boolean supported, boolean active) {
            this.supported = supported;
            this.active = active;
        }

        public boolean isSupported() {
            return supported;
        }

        public boolean isActive() {
            return active;
        }
    }
}
