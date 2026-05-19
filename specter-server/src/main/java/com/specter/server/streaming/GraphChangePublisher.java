package com.specter.server.streaming;

import com.specter.core.AnalysisProgressListener;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.core.graph.SpecterGraph;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Publishes graph analysis events over WebSocket STOMP (when available).
 * In STDIO mode SimpMessagingTemplate is absent — all publish calls
 * degrade to no-ops logged at INFO.
 */
@Slf4j
@Component
public class GraphChangePublisher implements AnalysisProgressListener {

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Override
    public void onProgress(String phase, int nodeCount, int edgeCount) {
        publishAnalysisProgress(phase, nodeCount, edgeCount);
    }

    @Override
    public void onComplete(SpecterGraph graph) {
        publishGraphSummary(graph);
    }

    /**
     * Called by SpecterAnalysisEngine after each analysis pass.
     */
    public void publishGraphSummary(SpecterGraph graph) {
        if (messagingTemplate == null) {
            log.info("WebSocket unavailable — skipping graph summary publish ({} nodes, {} edges)",
                    graph.nodeCount(), graph.edgeCount());
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeCount", graph.nodeCount());
            payload.put("edgeCount", graph.edgeCount());
            messagingTemplate.convertAndSend("/topic/graph-updates", payload);
        } catch (Exception e) {
            log.warn("Failed to publish graph summary over WebSocket", e);
        }
    }

    /**
     * Called when a health report is generated.
     */
    public void publishHealthUpdate(ArchitecturalHealthAnalyzer.HealthReport report) {
        if (messagingTemplate == null) {
            log.info("WebSocket unavailable — skipping health update publish (score: {})",
                    report.overallScore());
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("overallScore", report.overallScore());
            messagingTemplate.convertAndSend("/topic/health-updates", payload);
        } catch (Exception e) {
            log.warn("Failed to publish health update over WebSocket", e);
        }
    }

    /**
     * Called progressively during analysis to report progress.
     */
    public void publishAnalysisProgress(String phase, int nodeCount, int edgeCount) {
        if (messagingTemplate == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/analysis-progress",
                    Map.of("phase", phase, "nodes", nodeCount, "edges", edgeCount));
        } catch (Exception e) {
            log.warn("Failed to publish analysis progress over WebSocket", e);
        }
    }
}
