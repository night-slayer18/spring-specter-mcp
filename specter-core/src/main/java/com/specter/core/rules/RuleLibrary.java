package com.specter.core.rules;

import com.specter.core.graph.EdgeType;
import com.specter.core.graph.NodeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-configured library of standard architectural rules.
 */
public final class RuleLibrary {

    private RuleLibrary() {}

    public static final List<ArchitectureRule> STANDARD_RULES = List.of(

            new ArchitectureRule("ARCH-001",
                    "Controllers must not directly inject Repositories",
                    "ERROR", EdgeType.INJECTS, NodeType.CONTROLLER, NodeType.REPOSITORY,
                    "Bypass the service layer violates separation of concerns"),

            new ArchitectureRule("ARCH-002",
                    "Controllers must not directly inject DATA_REPOSITORY",
                    "ERROR", EdgeType.INJECTS, NodeType.CONTROLLER, NodeType.DATA_REPOSITORY,
                    "Spring Data repositories must be accessed through the service layer"),

            new ArchitectureRule("ARCH-003",
                    "Repositories must not depend on Services",
                    "ERROR", EdgeType.INJECTS, NodeType.REPOSITORY, NodeType.SERVICE,
                    "Dependency inversion violation — creates circular architecture"),

            new ArchitectureRule("ARCH-004",
                    "Controllers must not directly publish to message topics",
                    "WARNING", EdgeType.PUBLISHES_TO, NodeType.CONTROLLER, NodeType.MESSAGE_TOPIC,
                    "Message publishing should be delegated to a service or messaging component"),

            new ArchitectureRule("ARCH-005",
                    "External services must not be called directly from Controllers",
                    "WARNING", EdgeType.CALLS_REMOTE, NodeType.CONTROLLER, NodeType.EXTERNAL_SERVICE,
                    "Remote calls should be wrapped in service classes for proper error handling")
    );

    /**
     * Returns a mutable copy of the standard rules for customisation.
     */
    public static List<ArchitectureRule> mutableStandardRules() {
        return new ArrayList<>(STANDARD_RULES);
    }
}
