package com.specter.server.api;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

/**
 * REST management API for controlling Specter analysis, exporting graphs,
 * and monitoring architectural health without needing an MCP client.
 */
@RestController
@RequestMapping("/specter/api/v1")
public class SpecterManagementController {

    private final SpecterAnalysisEngine engine;

    public SpecterManagementController(SpecterAnalysisEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> body) {
        String sourceRoot = (String) body.getOrDefault("sourceRoot", "./src");
        @SuppressWarnings("unchecked")
        List<String> profiles = (List<String>) body.getOrDefault("activeProfiles", List.of());
        String jobId = UUID.randomUUID().toString();

        try {
            engine.analyze(Path.of(sourceRoot), new HashSet<>(profiles));
            return Map.of(
                    "jobId", jobId,
                    "status", "COMPLETED",
                    "nodeCount", engine.getGraph().nodeCount(),
                    "edgeCount", engine.getGraph().edgeCount()
            );
        } catch (Exception e) {
            return Map.of("jobId", jobId, "status", "FAILED", "error", e.getMessage());
        }
    }

    @GetMapping("/graph/summary")
    public Map<String, Object> getGraphSummary() {
        return engine.getGraphSummary();
    }

    @GetMapping("/graph/export")
    public Map<String, Object> exportGraph(@RequestParam(defaultValue = "json") String format) {
        return GraphExporter.export(engine.getGraph(), format);
    }

    @GetMapping(value = "/graph/export/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> exportGraphRaw(@RequestParam(defaultValue = "json") String format) {
        return GraphExporter.export(engine.getGraph(), format);
    }

    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        ArchitecturalHealthAnalyzer analyzer = new ArchitecturalHealthAnalyzer(engine.getGraph());
        var report = analyzer.analyze();
        return Map.of(
                "overallScore", report.overallScore(),
                "dimensions", report.dimensions().entrySet().stream()
                        .map(e -> Map.of(
                                "name", (Object) e.getKey(),
                                "score", e.getValue().score()))
                        .toList(),
                "criticalIssues", report.criticalIssues().size(),
                "recommendations", report.recommendations()
        );
    }

    @GetMapping(value = "/analysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public String streamAnalysis() {
        return "data: {\"status\":\"ready\"}\n\n";
    }
}
