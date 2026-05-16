package com.specter.core.rules;

import com.specter.core.graph.SpecterEdge;
import com.specter.core.graph.SpecterNode;

/**
 * Records a concrete violation of an {@link ArchitectureRule} in the
 * dependency graph — the specific source node, target node, and edge
 * that triggered the rule.
 */
public record RuleViolation(
        ArchitectureRule rule,
        SpecterNode source,
        SpecterNode target,
        SpecterEdge edge
) {
    @Override
    public String toString() {
        return rule.ruleId() + ": " + rule.description() + " — "
                + source.name() + " → " + target.name()
                + " [" + rule.severity() + "]";
    }
}
