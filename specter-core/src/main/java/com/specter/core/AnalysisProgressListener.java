package com.specter.core;

import com.specter.core.graph.SpecterGraph;

/**
 * Callback for publishing analysis progress events.
 * Implementations may stream to WebSocket, SSE, or any transport.
 * All methods must be non-blocking — a callback failure must not
 * abort the analysis pipeline.
 */
@FunctionalInterface
public interface AnalysisProgressListener {

    void onProgress(String phase, int nodeCount, int edgeCount);

    /**
     * Called after the full analysis completes with the final graph.
     * Default no-op.
     */
    default void onComplete(SpecterGraph graph) {}
}
