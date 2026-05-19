package com.specter.server.api;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.server.streaming.GraphChangePublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.micrometer.core.annotation.Timed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * REST management API for controlling Specter analysis, exporting graphs,
 * and monitoring architectural health without needing an MCP client.
 */
@ConditionalOnWebApplication
@RestController
@RequestMapping("/specter/api/v1")
@Timed("specter.api")
@Tag(name = "Specter Management API", description = "Analysis, graph export, and health monitoring endpoints")
@SecurityRequirement(name = "basicAuth")
public class SpecterManagementController {

    private final SpecterAnalysisEngine engine;

    @Autowired(required = false)
    private GraphChangePublisher publisher;

    public SpecterManagementController(SpecterAnalysisEngine engine) {
        this.engine = engine;
    }

    @Operation(
        summary = "Trigger architecture analysis",
        description = "Analyzes the specified source root and returns graph node/edge counts",
        responses = {
            @ApiResponse(responseCode = "200", description = "Analysis completed successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required")
        }
    )
    @PreAuthorize("hasRole('SPECTER_ADMIN')")
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

    @Operation(
        summary = "Get graph summary",
        description = "Returns a breakdown of all node and edge types in the architecture graph",
        responses = @ApiResponse(responseCode = "200", description = "Graph summary with type counts")
    )
    @PreAuthorize("hasRole('SPECTER_ADMIN')")
    @GetMapping("/graph/summary")
    public Map<String, Object> getGraphSummary() {
        return engine.getGraphSummary();
    }

    @Operation(
        summary = "Export graph",
        description = "Exports the full architecture graph in JSON or Mermaid format",
        responses = @ApiResponse(responseCode = "200", description = "Graph export data")
    )
    @PreAuthorize("hasRole('SPECTER_ADMIN')")
    @GetMapping("/graph/export")
    public Map<String, Object> exportGraph(
            @Parameter(description = "Export format: json or mermaid", example = "json")
            @RequestParam(defaultValue = "json") String format) {
        return GraphExporter.export(engine.getGraph(), format);
    }

    @Operation(
        summary = "Export raw graph (JSON)",
        description = "Exports the full architecture graph as raw JSON",
        responses = @ApiResponse(responseCode = "200", description = "Raw graph JSON")
    )
    @PreAuthorize("hasRole('SPECTER_ADMIN')")
    @GetMapping(value = "/graph/export/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> exportGraphRaw(
            @Parameter(description = "Export format: json", example = "json")
            @RequestParam(defaultValue = "json") String format) {
        return GraphExporter.export(engine.getGraph(), format);
    }

    @Operation(
        summary = "Get architectural health report",
        description = "Returns a comprehensive health score across all architectural dimensions (public endpoint)",
        responses = @ApiResponse(responseCode = "200", description = "Health report with dimension scores")
    )
    @GetMapping("/health")
    public Map<String, Object> getHealth() {
        ArchitecturalHealthAnalyzer analyzer = new ArchitecturalHealthAnalyzer(engine.getGraph());
        var report = analyzer.analyze();
        if (publisher != null) {
            publisher.publishHealthUpdate(report);
        }
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

    @Operation(
        summary = "Stream analysis events",
        description = "Opens an SSE stream that emits real-time analysis progress events",
        responses = @ApiResponse(responseCode = "200", description = "SSE stream connected")
    )
    @PreAuthorize("hasRole('SPECTER_ADMIN')")
    @GetMapping(value = "/analysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnalysis() {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        try {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("status", "connected");
            status.put("nodes", engine.getGraph().nodeCount());
            status.put("edges", engine.getGraph().edgeCount());
            status.put("activeBeans", engine.getRegistry().size());
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(status));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }
}
