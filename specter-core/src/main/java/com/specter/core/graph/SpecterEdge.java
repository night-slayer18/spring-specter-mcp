package com.specter.core.graph;

public record SpecterEdge(
    String sourceId,
    String targetId,
    EdgeType type
) {}
