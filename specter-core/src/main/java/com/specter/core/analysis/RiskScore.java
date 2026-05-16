package com.specter.core.analysis;

import java.util.Set;

/**
 * Standardized risk scoring model for architectural issues.
 * Used by security auditors, health analyzers, and violation detectors.
 */
public record RiskScore(String nodeId, String reason, int score, RiskLevel level) {

    public enum RiskLevel { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    private static final Set<String> DESTRUCTIVE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    public static RiskScore unprotectedEndpoint(String nodeId, String path, String httpVerb) {
        boolean isDestructive = DESTRUCTIVE_METHODS.contains(httpVerb.toUpperCase());
        RiskLevel level = isDestructive ? RiskLevel.CRITICAL : RiskLevel.HIGH;
        int score = isDestructive ? 90 : 70;
        return new RiskScore(nodeId,
                httpVerb + " " + path + " has no authentication constraint",
                score, level);
    }

    public static RiskScore csrfDisabled(String nodeId) {
        return new RiskScore(nodeId, "CSRF protection is disabled", 75, RiskLevel.HIGH);
    }

    public static RiskScore statelessSession(String nodeId) {
        return new RiskScore(nodeId, "Stateless session on stateful entity operations", 50, RiskLevel.MEDIUM);
    }

    public static RiskScore transactionalOnController(String nodeId) {
        return new RiskScore(nodeId, "@Transactional on controller layer", 45, RiskLevel.MEDIUM);
    }

    public static RiskScore circularDependency(String nodeId) {
        return new RiskScore(nodeId, "Circular dependency detected", 40, RiskLevel.MEDIUM);
    }

    public static RiskScore publicEndpointNoAuth(String nodeId, String path) {
        return new RiskScore(nodeId, "GET " + path + " returns entity data with no auth", 65, RiskLevel.HIGH);
    }

    public static RiskScore undocumentedEndpoint(String nodeId, String path, String verb) {
        return new RiskScore(nodeId,
                verb + " " + path + " is undocumented (no OpenAPI spec entry)",
                30, RiskLevel.LOW);
    }

    public static RiskScore undefinedConfigProperty(String nodeId, String key) {
        return new RiskScore(nodeId,
                "Config key '" + key + "' has no default and no properties file entry",
                80, RiskLevel.HIGH);
    }

    public static RiskScore resilienceGap(String nodeId, String serviceName) {
        return new RiskScore(nodeId,
                "Remote call to " + serviceName + " has no circuit breaker or retry",
                55, RiskLevel.MEDIUM);
    }

    public static RiskScore untestedComponent(String nodeId, String componentName) {
        return new RiskScore(nodeId,
                componentName + " has zero direct test coverage",
                35, RiskLevel.MEDIUM);
    }
}
