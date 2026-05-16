package com.specter.core.rules;

import com.specter.core.graph.SpecterEdge;
import com.specter.core.graph.SpecterGraph;
import com.specter.core.graph.SpecterNode;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Evaluates architecture rules against the live {@link SpecterGraph}.
 * Rules are edge-type constraints between node types and never mutate
 * the graph — they only produce {@link RuleViolation} records.
 *
 * <p>When constructed with a {@link Supplier}, the graph is resolved
 * lazily at evaluation time — allowing the rule engine to follow
 * project context switches without re-instantiation.
 */
public class ArchitectureRuleEngine {

    private final Supplier<SpecterGraph> graphSupplier;
    private final List<ArchitectureRule> customRules = new CopyOnWriteArrayList<>();

    public ArchitectureRuleEngine(SpecterGraph graph) {
        this(() -> graph);
    }

    public ArchitectureRuleEngine(Supplier<SpecterGraph> graphSupplier) {
        this.graphSupplier = graphSupplier;
    }

    /**
     * Evaluates both built-in and custom rules against the graph.
     */
    public List<RuleViolation> evaluate() {
        List<ArchitectureRule> allRules = new ArrayList<>();
        allRules.addAll(RuleLibrary.STANDARD_RULES);
        allRules.addAll(customRules);
        return evaluate(allRules);
    }

    /**
     * Evaluates a specific set of rules.
     */
    public List<RuleViolation> evaluate(List<ArchitectureRule> rules) {
        SpecterGraph graph = graphSupplier.get();
        List<RuleViolation> violations = new ArrayList<>();

        for (ArchitectureRule rule : rules) {
            for (SpecterEdge edge : graph.allEdges()) {
                if (edge.type() != rule.forbiddenEdge()) continue;

                SpecterNode source = graph.getNode(edge.sourceId()).orElse(null);
                SpecterNode target = graph.getNode(edge.targetId()).orElse(null);
                if (source == null || target == null) continue;

                if (source.type() == rule.sourceType() && target.type() == rule.targetType()) {
                    violations.add(new RuleViolation(rule, source, target, edge));
                }
            }
        }

        return violations;
    }

    /**
     * Adds a custom rule for evaluation on subsequent calls.
     */
    public void addCustomRule(ArchitectureRule rule) {
        customRules.add(rule);
    }

    /**
     * Removes a custom rule by ID.
     */
    public boolean removeCustomRule(String ruleId) {
        return customRules.removeIf(r -> r.ruleId().equals(ruleId));
    }

    /**
     * Returns all currently active custom rules.
     */
    public List<ArchitectureRule> customRules() {
        return List.copyOf(customRules);
    }
}
