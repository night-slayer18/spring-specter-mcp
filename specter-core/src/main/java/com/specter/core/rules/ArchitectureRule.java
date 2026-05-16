package com.specter.core.rules;

import com.specter.core.graph.EdgeType;
import com.specter.core.graph.NodeType;

/**
 * An architectural rule expressed as an edge-type constraint between
 * node types. Each rule declares a forbidden or undesirable edge between
 * two node types, with a severity level and human-readable rationale.
 */
public record ArchitectureRule(
        String ruleId,
        String description,
        String severity,
        EdgeType forbiddenEdge,
        NodeType sourceType,
        NodeType targetType,
        String rationale
) {}
