package eclipse.euphoriacompanion.util;

/**
 * Utility for formatting block and entity IDs with proper quoting and escaping.
 * Handles IDs that contain spaces, quotes, or backslashes.
 */
public class BlockIdFormatter {

    /**
     * Formats a block or entity ID for output, adding quotes and escaping if necessary.
     *
     * Rules:
     * - IDs with spaces, quotes, or backslashes are wrapped in double quotes
     * - Internal quotes are escaped as \"
     * - Internal backslashes are escaped as \\
     * - IDs without special characters are returned as-is
     *
     * Examples:
     * - "minecraft:stone" -> "minecraft:stone"
     * - "mod:block name" -> "mod:block name"
     * - "mod:block\"test" -> "mod:block\\\"test"
     * - "mod:path\\to\\block" -> "mod:path\\\\to\\\\block"
     *
     * @param id The block or entity ID to format
     * @param enableQuoting Whether to enable quoting (if false, returns ID as-is)
     * @return Formatted ID with proper quoting and escaping
     */
    public static String formatId(String id, boolean enableQuoting) {
        if (id == null || id.isEmpty()) {
            return id;
        }

        if (!enableQuoting) {
            return id;
        }

        // Check if the ID needs quoting (contains space, quote, or backslash)
        boolean needsQuoting = id.contains(" ") || id.contains("\"") || id.contains("\\");

        if (!needsQuoting) {
            return id;
        }

        // Escape backslashes first (must be done before escaping quotes)
        String escaped = id.replace("\\", "\\\\");

        // Then escape quotes
        escaped = escaped.replace("\"", "\\\"");

        // Wrap in quotes
        return "\"" + escaped + "\"";
    }

    /**
     * Unformats a block or entity ID by removing quotes and unescaping.
     * This is the inverse of formatId().
     *
     * @param formattedId The formatted ID (potentially quoted)
     * @return Unescaped ID without quotes
     */
    public static String unformatId(String formattedId) {
        if (formattedId == null || formattedId.isEmpty()) {
            return formattedId;
        }

        String trimmed = formattedId.trim();

        // Check if wrapped in quotes
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            // Remove surrounding quotes
            String withoutQuotes = trimmed.substring(1, trimmed.length() - 1);

            // Unescape quotes
            String unescaped = withoutQuotes.replace("\\\"", "\"");

            // Unescape backslashes (must be done after unescaping quotes)
            unescaped = unescaped.replace("\\\\", "\\");

            return unescaped;
        }

        // Not quoted, return as-is
        return trimmed;
    }

    /**
     * Checks if an ID needs to be quoted.
     *
     * @param id The ID to check
     * @return true if the ID contains spaces or special characters
     */
    public static boolean needsQuoting(String id) {
        return id != null && (id.contains(" ") || id.contains("\"") || id.contains("\\"));
    }
}
