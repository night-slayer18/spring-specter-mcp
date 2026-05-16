package com.specter.core.graph;

public enum EdgeType {
    INJECTS,
    CALLS,
    MAPS_TO,
    PUBLISHES_TO,
    SUBSCRIBES_FROM,
    IMPLEMENTS,
    EXTENDS,
    ANNOTATED_WITH,
    CONTAINS
}
