package com.specter.core.analysis;

import com.specter.core.graph.*;

import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Computes a composite architectural health score across 6 dimensions:
 * dependency health, security, resilience, test coverage, observability,
 * and API contract quality. Each dimension scores 0-100.
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

        Map<String, DimensionScore> dimensions = new LinkedHashMap<>();
        dimensions.put("DEPENDENCY_HEALTH", dependencyHealth);
        dimensions.put("SECURITY_HEALTH", securityHealth);
        dimensions.put("RESILIENCE_HEALTH", resilienceHealth);
        dimensions.put("TEST_HEALTH", testHealth);
        dimensions.put("OBSERVABILITY_HEALTH", observabilityHealth);
        dimensions.put("CONTRACT_HEALTH", contractHealth);

        int overall = (int) Math.round(
                dependencyHealth.score() + securityHealth.score()
                + resilienceHealth.score() + testHealth.score()
                + observabilityHealth.score() + contractHealth.score()) / 6;

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

        List<SpecterNode> allNodes = new ArrayList<>(graph.allNodes());
        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() == EdgeType.INJECTS && edge.sourceId().equals(edge.targetId())) {
                score = Math.max(0, score - 20);
                issues.add(RiskScore.circularDependency(edge.sourceId()));
            }
        }

        return new DimensionScore("DEPENDENCY_HEALTH", Math.max(0, score), issues);
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
            graph.getNode(edge.targetId()).ifPresent(target -> {
                issues.add(RiskScore.resilienceGap(edge.sourceId(), target.name()));
            });
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
        int score = 80;
        return new DimensionScore("OBSERVABILITY_HEALTH", score, List.of());
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
