package eclipse.euphoriacompanion.report;

import eclipse.euphoriacompanion.analyzer.ShaderAnalyzer.RenderLayerMismatch;

import java.util.*;

/**
 * Contains the results of a shader analysis.
 */
public class AnalysisReport {
    private final String shaderpackName;
    private Map<String, Map<String, List<String>>> missingBlocksByMod = new HashMap<>();
    private Map<String, Set<String>> tagCoverage = new HashMap<>();
    private Map<String, String> tagDefinitions = new HashMap<>();
    private Map<String, Integer> tagToProperty = new HashMap<>();
    private Map<String, RenderLayerMismatch> renderLayerMismatches = new HashMap<>();
    private Map<String, Map<String, List<String>>> incompleteBlockStates = new HashMap<>();
    private Map<String, List<Integer>> duplicateDefinitions = new HashMap<>();

    // Statistics
    private int totalBlocksInGame = 0;
    private int totalBlocksInShader = 0;
    private boolean tagSupportEnabled = false;

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

    public Map<String, Set<String>> getTagCoverage() {
        return tagCoverage;
    }

    public void setTagCoverage(Map<String, Set<String>> tagCoverage) {
        this.tagCoverage = tagCoverage;
    }

    public Map<String, String> getTagDefinitions() {
        return tagDefinitions;
    }

    public void setTagDefinitions(Map<String, String> tagDefinitions) {
        this.tagDefinitions = tagDefinitions;
    }

    public Map<String, Integer> getTagToProperty() {
        return tagToProperty;
    }

    public void setTagToProperty(Map<String, Integer> tagToProperty) {
        this.tagToProperty = tagToProperty;
    }

    public Map<String, RenderLayerMismatch> getRenderLayerMismatches() {
        return renderLayerMismatches;
    }

    public void setRenderLayerMismatches(Map<String, RenderLayerMismatch> renderLayerMismatches) {
        this.renderLayerMismatches = renderLayerMismatches;
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

    public boolean isTagSupportEnabled() {
        return tagSupportEnabled;
    }

    public void setTagSupportEnabled(boolean tagSupportEnabled) {
        this.tagSupportEnabled = tagSupportEnabled;
    }

    /**
     * Gets the total count of missing blocks across all mods
     */
    public int getTotalMissingBlocks() {
        return missingBlocksByMod.values().stream()
            .mapToInt(categories -> categories.values().stream()
                .mapToInt(List::size)
                .sum())
            .sum();
    }

}