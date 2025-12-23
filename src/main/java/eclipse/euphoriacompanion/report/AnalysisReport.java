package eclipse.euphoriacompanion.report;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contains the results of a shader analysis.
 */
public class AnalysisReport {
    private final String shaderpackName;
    private Map<String, Map<String, List<String>>> missingBlocksByMod = new HashMap<>();
    private Map<String, Map<String, List<String>>> incompleteBlockStates = new HashMap<>();
    private Map<String, List<Integer>> duplicateDefinitions = new HashMap<>();

    // Statistics
    private int totalBlocksInGame = 0;
    private int totalBlocksInShader = 0;

    public AnalysisReport(String shaderpackName) {
        this.shaderpackName = shaderpackName;
    }

    // Getters and setters
    public String getShaderpackName() {
        return shaderpackName;
    }

    public Map<String, Map<String, List<String>>> getMissingBlocksByMod() {
        return missingBlocksByMod;
    }

    public void setMissingBlocksByMod(Map<String, Map<String, List<String>>> missingBlocksByMod) {
        this.missingBlocksByMod = missingBlocksByMod;
    }

    public Map<String, Map<String, List<String>>> getIncompleteBlockStates() {
        return incompleteBlockStates;
    }

    public void setIncompleteBlockStates(Map<String, Map<String, List<String>>> incompleteBlockStates) {
        this.incompleteBlockStates = incompleteBlockStates;
    }

    public Map<String, List<Integer>> getDuplicateDefinitions() {
        return duplicateDefinitions;
    }

    public void setDuplicateDefinitions(Map<String, List<Integer>> duplicateDefinitions) {
        this.duplicateDefinitions = duplicateDefinitions;
    }

    public int getTotalBlocksInGame() {
        return totalBlocksInGame;
    }

    public void setTotalBlocksInGame(int totalBlocksInGame) {
        this.totalBlocksInGame = totalBlocksInGame;
    }

    public int getTotalBlocksInShader() {
        return totalBlocksInShader;
    }

    public void setTotalBlocksInShader(int totalBlocksInShader) {
        this.totalBlocksInShader = totalBlocksInShader;
    }

    /**
     * Gets the total count of missing blocks across all mods
     */
    public int getTotalMissingBlocks() {
        int total = 0;
        for (Map<String, List<String>> categories : missingBlocksByMod.values()) {
            for (List<String> blocks : categories.values()) {
                total += blocks.size();
            }
        }
        return total;
    }

}