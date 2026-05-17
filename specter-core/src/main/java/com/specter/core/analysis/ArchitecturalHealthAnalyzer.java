package com.specter.core.analysis;

import com.specter.core.graph.*;
import com.specter.core.rules.ArchitectureRuleEngine;
import com.specter.core.rules.RuleViolation;

import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Computes a composite architectural health score across 7 dimensions:
 * dependency health, security, resilience, test coverage, observability,
 * API contract quality, and architecture rule compliance.
 * Each dimension scores 0-100.
 */
@Slf4j
public class ArchitecturalHealthAnalyzer {

    private final SpecterGraph graph;

    public ArchitecturalHealthAnalyzer(SpecterGraph graph) {
        this.graph = graph;
    }

    public HealthReport analyze() {
        DimensionScore dependencyHealth = computeDependencyHealth();
        DimensionScore securityHealth = computeSecurityHealth();
        DimensionScore resilienceHealth = computeResilienceHealth();
        DimensionScore testHealth = computeTestHealth();
        DimensionScore observabilityHealth = computeObservabilityHealth();
        DimensionScore contractHealth = computeContractHealth();
        DimensionScore architectureRulesHealth = computeArchitectureRulesHealth();

        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        dimensions.put("DEPENDENCY_HEALTH", dependencyHealth);
        dimensions.put("SECURITY_HEALTH", securityHealth);
        dimensions.put("RESILIENCE_HEALTH", resilienceHealth);
        dimensions.put("TEST_HEALTH", testHealth);
        dimensions.put("OBSERVABILITY_HEALTH", observabilityHealth);
        dimensions.put("CONTRACT_HEALTH", contractHealth);
        dimensions.put("ARCHITECTURE_RULES_HEALTH", architectureRulesHealth);

        // Use mapToInt().average() — division happens on a double inside Math.round(),
        // avoiding the integer-truncation bug of the previous (sum)/7 formula.
        int overall = (int) Math.round(
                dimensions.values().stream()
                        .mapToInt(DimensionScore::score)
                        .average()
                        .orElse(100.0));


        List<RiskScore> criticalIssues = new ArrayList<>();
        for (DimensionScore ds : dimensions.values()) {
            criticalIssues.addAll(ds.criticalIssues());
        }

        List<String> recommendations = buildRecommendations(dimensions);

        return new HealthReport(overall, dimensions, criticalIssues, recommendations);
    }

    private DimensionScore computeDependencyHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<List<String>> allCycles = new ArrayList<>();

        for (SpecterNode node : graph.allNodes()) {
            if (!visited.contains(node.id())) {
                dfsFindCycles(node.id(), visited, inStack, new LinkedList<>(), allCycles);
            }
        }

        for (List<String> cycle : allCycles) {
            score = Math.max(0, score - 20);
            issues.add(RiskScore.circularDependency(String.join(" → ", cycle)));
        }

        return new DimensionScore("DEPENDENCY_HEALTH", Math.max(0, score), issues);
    }

    private void dfsFindCycles(String nodeId, Set<String> visited, Set<String> inStack,
                                LinkedList<String> path, List<List<String>> cycles) {
        visited.add(nodeId);
        inStack.add(nodeId);
        path.addLast(nodeId);

        for (SpecterEdge edge : graph.getOutgoingEdges(nodeId)) {
            if (edge.type() != EdgeType.INJECTS) continue;
            String target = edge.targetId();
            if (!visited.contains(target)) {
                dfsFindCycles(target, visited, inStack, path, cycles);
            } else if (inStack.contains(target)) {
                int cycleStart = path.indexOf(target);
                if (cycleStart >= 0) {
                    List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
                    cycle.add(target);
                    cycles.add(cycle);
                }
            }
        }

        path.removeLast();
        inStack.remove(nodeId);
    }

    private DimensionScore computeSecurityHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();

        for (SpecterNode ep : graph.findNodesByType(NodeType.CONTROLLER_ENDPOINT)) {
            String httpVerb = ep.metadata().getOrDefault("httpVerb", "GET");
            String path = ep.metadata().getOrDefault("path", "/");
            boolean secured = graph.getIncomingEdges(ep.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.SECURED_BY)
                    || graph.getOutgoingEdges(ep.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.SECURED_BY);

            if (!secured) {
                issues.add(RiskScore.unprotectedEndpoint(ep.id(), path, httpVerb));
                score = Math.max(0, score - ("POST PUT DELETE PATCH".contains(httpVerb.toUpperCase()) ? 25 : 15));
            }
        }

        for (SpecterNode flag : graph.findNodesByType(NodeType.SECURITY_FLAG)) {
            if ("CSRF_DISABLED".equals(flag.metadata().get("flag"))) {
                score = Math.max(0, score - 20);
                issues.add(RiskScore.csrfDisabled(flag.id()));
            }
        }

        return new DimensionScore("SECURITY_HEALTH", Math.max(0, score), issues);
    }

    private DimensionScore computeResilienceHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();

        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() != EdgeType.CALLS_REMOTE) continue;

            // only penalise callers that have no retry/circuit-breaker proxy.
            // AopProxyResolver creates PROXY nodes with PROXY_STEREOTYPE metadata for
            // @Retryable and @CircuitBreaker and connects them via CALLS edges.
            boolean hasResilience = graph.getIncomingEdges(edge.sourceId()).stream()
                    .anyMatch(e -> e.type() == EdgeType.CALLS
                            && graph.getNode(e.sourceId())
                                    .map(n -> n.type() == NodeType.PROXY
                                            && (n.metadata().getOrDefault("PROXY_STEREOTYPE", "")
                                                    .contains("RETRY_INTERCEPTOR")
                                               || n.metadata().getOrDefault("PROXY_STEREOTYPE", "")
                                                    .contains("CIRCUIT_BREAKER_INTERCEPTOR")))
                                    .orElse(false));

            if (!hasResilience) {
                graph.getNode(edge.targetId()).ifPresent(target ->
                        issues.add(RiskScore.resilienceGap(edge.sourceId(), target.name())));
            }
        }

        score = Math.max(0, score - issues.size() * 15);
        return new DimensionScore("RESILIENCE_HEALTH", score, issues);
    }

    private DimensionScore computeTestHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();

        for (SpecterNode node : graph.allNodes()) {
            NodeType type = node.type();
            if (type != NodeType.SERVICE && type != NodeType.REPOSITORY
                    && type != NodeType.COMPONENT) continue;
            boolean tested = graph.getIncomingEdges(node.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.TESTS);
            if (!tested) {
                score = Math.max(0, score - 5);
                issues.add(RiskScore.untestedComponent(node.id(), node.name()));
            }
        }

        return new DimensionScore("TEST_HEALTH", score, issues);
    }

    private DimensionScore computeObservabilityHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();

        Set<String> serviceControllerIds = new HashSet<>();
        for (SpecterNode node : graph.allNodes()) {
            if (node.type() == NodeType.SERVICE || node.type() == NodeType.CONTROLLER) {
                serviceControllerIds.add(node.id());
            }
        }

        if (serviceControllerIds.isEmpty()) {
            return new DimensionScore("OBSERVABILITY_HEALTH", 100, issues);
        }

        for (String nodeId : serviceControllerIds) {
            boolean hasMeasures = false;
            boolean hasTraces = false;
            boolean hasMeterRegistry = "true".equals(
                    graph.getNode(nodeId).map(n -> n.metadata().get("hasMeterRegistry")).orElse("false"));
            boolean instrumented = "true".equals(
                    graph.getNode(nodeId).map(n -> n.metadata().get("instrumented")).orElse("false"));

            for (SpecterEdge edge : graph.getOutgoingEdges(nodeId)) {
                if (edge.type() == EdgeType.MEASURES) hasMeasures = true;
                if (edge.type() == EdgeType.TRACES) hasTraces = true;
            }

            if (!hasMeasures && !hasTraces) {
                score = Math.max(0, score - 8);
                SpecterNode node = graph.getNode(nodeId).orElse(null);
                if (node != null) {
                    issues.add(new RiskScore(nodeId,
                            node.name() + " has no metrics and no tracing instrumentation",
                            8, RiskScore.RiskLevel.MEDIUM));
                }
            }

            if (!hasMeterRegistry && !instrumented) {
                score = Math.max(0, score - 5);
                SpecterNode node = graph.getNode(nodeId).orElse(null);
                if (node != null) {
                    issues.add(new RiskScore(nodeId,
                            node.name() + " has no @Timed and no MeterRegistry injection",
                            5, RiskScore.RiskLevel.MEDIUM));
                }
            }
        }

        List<SpecterNode> healthIndicators = graph.findNodesByType(NodeType.HEALTH_INDICATOR);
        for (SpecterNode repo : graph.findNodesByType(NodeType.DATA_REPOSITORY)) {
            boolean hasHealthIndicator = false;
            for (SpecterNode hi : healthIndicators) {
                if (graph.getOutgoingEdges(repo.id()).stream()
                        .anyMatch(e -> e.targetId().equals(hi.id()) && e.type() == EdgeType.IMPLEMENTS)) {
                    hasHealthIndicator = true;
                    break;
                }
            }
            if (!hasHealthIndicator) {
                score = Math.max(0, score - 10);
                issues.add(new RiskScore(repo.id(),
                        repo.name() + " has no HealthIndicator",
                        10, RiskScore.RiskLevel.HIGH));
            }
        }

        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() != EdgeType.CALLS_REMOTE) continue;
            String sourceId = edge.sourceId();
            boolean hasSpanAnnotation = graph.getOutgoingEdges(sourceId).stream()
                    .anyMatch(e -> e.type() == EdgeType.TRACES);
            if (!hasSpanAnnotation) {
                score = Math.max(0, score - 12);
                graph.getNode(edge.targetId()).ifPresent(target -> {
                    issues.add(new RiskScore(sourceId,
                            "Cross-service call to " + target.name() + " has no @NewSpan or @WithSpan",
                            12, RiskScore.RiskLevel.HIGH));
                });
            }
        }

        return new DimensionScore("OBSERVABILITY_HEALTH", Math.max(0, score), issues);
    }

    private DimensionScore computeContractHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();

        for (SpecterNode ep : graph.findNodesByType(NodeType.CONTROLLER_ENDPOINT)) {
            boolean documented = "true".equals(ep.metadata().get("hasOpenApiAnnotation"))
                    || "true".equals(ep.metadata().get("hasOpenApiSpec"));
            if (!documented) {
                score = Math.max(0, score - 10);
                issues.add(RiskScore.undocumentedEndpoint(ep.id(),
                        ep.metadata().getOrDefault("path", "/"),
                        ep.metadata().getOrDefault("httpVerb", "GET")));
            }
        }

        for (SpecterNode key : graph.findNodesByType(NodeType.CONFIG_KEY)) {
            if (!"true".equals(key.metadata().get("hasDefault"))
                    && graph.getOutgoingEdges(key.id()).stream()
                    .noneMatch(e -> e.type() == EdgeType.RESOLVES_TO)) {
                score = Math.max(0, score - 8);
                issues.add(RiskScore.undefinedConfigProperty(key.id(),
                        key.metadata().getOrDefault("propertyKey", "unknown")));
            }
        }

        return new DimensionScore("CONTRACT_HEALTH", score, issues);
    }

    private DimensionScore computeArchitectureRulesHealth() {
        int score = 100;
        List<RiskScore> issues = new ArrayList<>();

        ArchitectureRuleEngine ruleEngine = new ArchitectureRuleEngine(graph);
        List<RuleViolation> violations = ruleEngine.evaluate();

        for (RuleViolation v : violations) {
            int penalty = "ERROR".equals(v.rule().severity()) ? 20 : 10;
            score = Math.max(0, score - penalty);
            issues.add(new RiskScore(v.source().id(),
                    v.rule().ruleId() + ": " + v.rule().description() + " ("
                            + v.source().name() + " → " + v.target().name() + ")",
                    penalty,
                    "ERROR".equals(v.rule().severity())
                            ? RiskScore.RiskLevel.HIGH
                            : RiskScore.RiskLevel.MEDIUM));
        }

        return new DimensionScore("ARCHITECTURE_RULES_HEALTH", score, issues);
    }

    private List<String> buildRecommendations(Map<String, DimensionScore> dimensions) {
        List<String> recs = new ArrayList<>();
        for (var entry : dimensions.entrySet()) {
            if (entry.getValue().score() < 60) {
                recs.add("CRITICAL: " + entry.getKey() + " score is " + entry.getValue().score()
                        + " — immediate remediation needed with "
                        + entry.getValue().criticalIssues().size() + " issues");
            } else if (entry.getValue().score() < 80) {
                recs.add("IMPROVE: " + entry.getKey() + " score is " + entry.getValue().score()
                        + " — address " + entry.getValue().criticalIssues().size() + " issues");
            }
        }
        if (recs.isEmpty()) {
            recs.add("All dimensions are healthy — no critical issues found.");
        }
        return recs;
    }

    public record HealthReport(
            int overallScore,
            Map<String, DimensionScore> dimensions,
            List<RiskScore> criticalIssues,
            List<String> recommendations
    ) {}

    public record DimensionScore(
            String name,
            int score,
            List<RiskScore> criticalIssues
    ) {}
}
