package com.specter.server.remediation;

import com.specter.core.graph.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * AI-powered remediation engine. Calls the Anthropic Claude API to
 * generate specific, minimal code fixes for architectural issues
 * detected by Specter.
 *
 * <p>The API is only invoked when {@code suggestFix} is called explicitly
 * by an MCP tool — never during analysis passes.
 */
@Slf4j
@Component
public class RemediationEngine {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:java|\\w*)?\\s*\\n(.*?)```", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final String apiKey;

    public RemediationEngine(
            @Value("${anthropic.api.key:}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Generates a specific code fix for a given architectural issue.
     *
     * @param nodeId  the graph node ID of the affected component
     * @param issue   human-readable description of the issue
     * @param graph   the current analysis graph
     * @return a suggestion with before/after code snippets
     */
    public RemediationSuggestion suggestFix(String nodeId, String issue, SpecterGraph graph) {
        if (apiKey == null || apiKey.isBlank()) {
            return new RemediationSuggestion(nodeId, issue,
                "Anthropic API key not configured. Set ANTHROPIC_API_KEY to enable AI-powered remediation.",
                List.of(), false, "N/A");
        }

        String context = buildGraphContext(nodeId, graph);
        String prompt = buildPrompt(nodeId, issue, context);

        try {
            String response = callClaude(prompt);
            List<String> snippets = extractCodeSnippets(response);
            boolean downtime = response.toLowerCase().contains("downtime required")
                    || response.toLowerCase().contains("requires downtime");
            String effort = estimateEffort(response);

            return new RemediationSuggestion(nodeId, issue, response, snippets, downtime, effort);
        } catch (Exception e) {
            log.error("Anthropic API call failed for nodeId={}", nodeId, e);
            return new RemediationSuggestion(nodeId, issue,
                    "Failed to reach Anthropic API: " + e.getMessage(),
                    List.of(), false, "N/A");
        }
    }

    private String buildGraphContext(String nodeId, SpecterGraph graph) {
        StringBuilder sb = new StringBuilder();

        graph.getNode(nodeId).ifPresent(node -> {
            sb.append("Component: ").append(node.name())
                    .append(" [" + node.type() + "]\n");
            node.metadata().forEach((k, v) ->
                    sb.append("  ").append(k).append(": ").append(v).append("\n"));
        });

        sb.append("\nDependencies:\n");
        for (SpecterEdge edge : graph.getOutgoingEdges(nodeId)) {
            graph.getNode(edge.targetId()).ifPresent(target ->
                    sb.append("  → ").append(target.name())
                            .append(" [").append(target.type()).append("]")
                            .append(" via ").append(edge.type()).append("\n"));
        }

        sb.append("\nConsumed by:\n");
        for (SpecterEdge edge : graph.getIncomingEdges(nodeId)) {
            graph.getNode(edge.sourceId()).ifPresent(source ->
                    sb.append("  ← ").append(source.name())
                            .append(" [").append(source.type()).append("]")
                            .append(" via ").append(edge.type()).append("\n"));
        }

        return sb.toString();
    }

    private String buildPrompt(String nodeId, String issue, String context) {
        return """
            You are a Spring Boot architect. Analyze this architectural issue and provide
            a specific, minimal code fix.

            ISSUE: %s
            AFFECTED_NODE: %s

            GRAPH_CONTEXT:
            %s

            Provide a concise response with:
            1. Exact code before (current state)
            2. Exact code after (proposed fix)
            3. Whether any downtime is required ("No downtime" or "Downtime required: explain")
            4. Effort estimate: Small (<1h), Medium (1-4h), Large (4h+)

            Be specific — use actual Spring annotations and real code patterns.
            """.formatted(issue, nodeId, context);
    }

    private String callClaude(String prompt) throws IOException, InterruptedException {
        String body = """
            {
                "model": "claude-sonnet-4-20250514",
                "max_tokens": 2048,
                "messages": [{"role": "user", "content": %s}]
            }
            """.formatted(jsonEscape(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Anthropic API returned " + response.statusCode()
                    + ": " + response.body());
        }

        return parseClaudeResponse(response.body());
    }

    private String parseClaudeResponse(String responseBody) {
        // Simple JSON extraction — find the text content in Claude's response format
        Pattern contentPattern = Pattern.compile(
                "\"text\"\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\"", Pattern.DOTALL);
        Matcher m = contentPattern.matcher(responseBody);

        StringBuilder result = new StringBuilder();
        while (m.find()) {
            String text = m.group(1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            result.append(text);
        }

        if (result.isEmpty()) {
            return "Unable to parse Claude's response. Raw: " + responseBody;
        }

        return result.toString();
    }

    private List<String> extractCodeSnippets(String response) {
        List<String> snippets = new ArrayList<>();
        Matcher m = CODE_BLOCK.matcher(response);
        while (m.find()) {
            snippets.add(m.group(1).trim());
        }
        return snippets;
    }

    private String estimateEffort(String response) {
        String lower = response.toLowerCase();
        if (lower.contains("large (4h+)")) return "Large (4h+)";
        if (lower.contains("medium (1-4h)")) return "Medium (1-4h)";
        if (lower.contains("small (<1h)")) return "Small (<1h)";
        if (lower.contains("effort estimate:")) {
            int idx = lower.indexOf("effort estimate:");
            return response.substring(idx, Math.min(idx + 60, response.length()))
                    .replaceFirst("(?i)effort estimate:\\s*", "");
        }
        return "Medium (1-4h)";
    }

    private String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
