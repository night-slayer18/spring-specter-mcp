package com.specter.server.tools;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.SpecterAnalysisEngine.*;
import com.specter.core.rules.ArchitectureRuleEngine;
import com.specter.core.rules.ArchitectureRule;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer.DimensionScore;
import com.specter.core.analysis.GraphDiff;
import com.specter.core.analysis.RiskScore;
import com.specter.core.graph.EdgeType;
import com.specter.core.graph.NodeType;
import com.specter.core.graph.SpecterEdge;
import com.specter.core.graph.SpecterNode;
import com.specter.core.persistence.GraphSerializer;
import com.specter.server.ProjectRegistry;
import com.specter.server.ProjectRegistry.ProjectContext;
import com.specter.server.remediation.RemediationEngine;
import com.specter.core.provenance.GitProvenanceChecker;
import com.specter.core.provenance.ProvenanceViolation;
import com.specter.core.watcher.SourceChangeTracker.ChangeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SpecterMcpTools {

    private final AtomicReference<SpecterAnalysisEngine> activeEngine;
    private final ArchitectureRuleEngine ruleEngine;
    private final AtomicReference<String> activeSourceRoot;  // updated on switchProject
    private final ProjectRegistry projectRegistry;
    private final RemediationEngine remediationEngine;

    public SpecterMcpTools(SpecterAnalysisEngine engine,
                            @Value("${specter.source.root:./src}") String sourceRootPath,
                            ProjectRegistry projectRegistry,
                            RemediationEngine remediationEngine) {
        this.activeEngine = new AtomicReference<>(engine);
        this.ruleEngine = new ArchitectureRuleEngine(() -> activeEngine.get().getGraph());
        this.activeSourceRoot = new AtomicReference<>(sourceRootPath);
        this.projectRegistry = projectRegistry;
        this.remediationEngine = remediationEngine;
    }

    @Tool(name = "search_architecture",
            description = "Full-text fuzzy search across the analyzed codebase with heavy weighting on " +
                    "framework stereotypes (DATA_REPOSITORY, MESSAGE_CONSUMER, PERSISTENCE_ENTITY, CONTROLLER, PROXY, etc.). " +
                    "Returns matching components with types, metadata, and relevance scores. " +
                    "Example queries: 'Find all MESSAGE_CONSUMER nodes handling order_events', " +
                    "'PERSISTENCE_ENTITY user', 'TRANSACTION_INTERCEPTOR proxy'")
    public List<Map<String, Object>> searchArchitecture(
            @ToolParam(description = "Natural language or stereotype-keyword query for architectural components") String query,
            @ToolParam(description = "Maximum number of results to return (default 10)") int maxResults) {

        return activeEngine.get().search(query, maxResults).stream()
                .map(hit -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("nodeId", hit.nodeId());
                    result.put("name", hit.name());
                    result.put("type", hit.type());
                    result.put("relevanceScore", hit.score());
                    return result;
                })
                .toList();
    }

    @Tool(name = "simulate_dependency_injection",
            description = "Simulates Spring's runtime dependency injection for a given type or interface. " +
                    "Resolves @Primary and @Qualifier annotations to determine exactly which concrete class " +
                    "Spring will inject at runtime. Returns the resolved implementation and all candidate beans. " +
                    "Example: 'UserService' with @Qualifier('userServiceImpl') returns the exact implementing class.")
    public Map<String, Object> simulateDependencyInjection(
            @ToolParam(description = "Type or interface name to resolve (e.g., 'UserService', 'com.example.UserRepository')") String interfaceName,
            @ToolParam(description = "Optional @Qualifier value to disambiguate multiple implementations") String qualifier) {

        InjectionSimulation result = activeEngine.get().simulateDependencyInjection(interfaceName,
                qualifier != null && !qualifier.isBlank() ? qualifier : null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("interfaceName", result.interfaceName());
        response.put("qualifier", result.qualifier());
        response.put("resolvedClass", result.resolvedClass());
        response.put("resolved", result.resolvedClass() != null);
        response.put("candidates", result.candidates());
        response.put("totalCandidates", result.candidates().size());
        response.put("ambiguous", result.resolvedClass() == null && !result.candidates().isEmpty());

        if (result.resolvedClass() == null && !result.candidates().isEmpty()) {
            response.put("message", "Multiple candidates found without @Primary or matching @Qualifier — injection is ambiguous");
        } else if (result.candidates().isEmpty()) {
            response.put("message", "No active beans found implementing " + interfaceName);
        }

        return response;
    }

    @Tool(name = "get_transaction_boundaries",
            description = "Traverses the service execution call chain from a given class and identifies all " +
                    "transaction boundaries (@Transactional proxy interceptors) along the path. " +
                    "Critical for understanding where database transactions begin and end in a request flow. " +
                    "Returns ordered list of PROXY nodes with TRANSACTION_INTERCEPTOR stereotypes.")
    public Map<String, Object> getTransactionBoundaries(
            @ToolParam(description = "Fully qualified or simple class name to start traversal from (e.g., 'OrderService')") String className,
            @ToolParam(description = "Maximum traversal depth along CALLS edges (default 5)") int maxDepth) {

        TransactionBoundaryResult result = activeEngine.get().getTransactionBoundaries(className, maxDepth);

        List<Map<String, Object>> boundaryNodes = result.boundaries().stream()
                .map(SpecterMcpTools::nodeToMap)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("className", result.className());
        response.put("searchDepth", maxDepth);
        response.put("totalBoundaries", boundaryNodes.size());
        response.put("boundaries", boundaryNodes);

        if (boundaryNodes.isEmpty()) {
            response.put("message", "No @Transactional boundaries found along the call chain from " + className);
        }

        return response;
    }

    @Tool(name = "calculate_blast_radius",
            description = "Executes a multi-degree bidirectional graph traversal from the given class name. " +
                    "Resolves through proxy interfaces to concrete implementations before calculating impact. " +
                    "Returns all downstream dependencies and upstream dependents affected by a change.")
    public Map<String, Object> calculateBlastRadius(
            @ToolParam(description = "Fully qualified or simple class name to analyze") String className,
            @ToolParam(description = "Maximum traversal depth in the dependency graph (default 3)") int maxDepth) {

        BlastRadiusResult result = activeEngine.get().calculateBlastRadius(className, maxDepth);

        List<Map<String, Object>> affectedNodes = result.affectedNodes().stream()
                .map(SpecterMcpTools::nodeToMap)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("targetClass", result.className());
        response.put("searchDepth", maxDepth);
        response.put("totalAffected", affectedNodes.size());
        response.put("affectedNodes", affectedNodes);

        return response;
    }

    @Tool(name = "trace_message_flow",
            description = "Traces the complete producer-to-consumer chain for a messaging channel across " +
                    "any supported technology (Kafka, RabbitMQ, JMS, Spring Cloud Stream). " +
                    "Returns all producers publishing to the channel and all consumers subscribing from it.")
    public Map<String, Object> traceMessageFlow(
            @ToolParam(description = "Messaging channel, topic, or queue name to trace") String channelName) {

        MessageFlowResult result = activeEngine.get().traceMessageFlow(channelName);

        List<Map<String, Object>> producerNodes = result.producers().stream()
                .map(SpecterMcpTools::nodeToMap)
                .toList();
        List<Map<String, Object>> consumerNodes = result.consumers().stream()
                .map(SpecterMcpTools::nodeToMap)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("channelName", result.channelName());
        response.put("producers", producerNodes);
        response.put("consumers", consumerNodes);
        response.put("totalProducers", producerNodes.size());
        response.put("totalConsumers", consumerNodes.size());

        return response;
    }

    @Tool(name = "analyze_dependency_cycle",
            description = "Traverses all INJECTS edges in the Spring dependency graph and detects circular " +
                    "dependency loops. Returns each cycle as an ordered list of component nodes. " +
                    "Critical for identifying design flaws before they cause runtime failures.")
    public Map<String, Object> analyzeDependencyCycle() {
        DependencyCycleResult result = activeEngine.get().analyzeDependencyCycle();

        List<List<Map<String, Object>>> cycleDetails = result.cycles().stream()
                .map(cycle -> cycle.stream()
                        .map(SpecterMcpTools::nodeToMap)
                        .toList())
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hasCycles", result.hasCycles());
        response.put("totalCycles", result.cycles().size());
        response.put("cycles", cycleDetails);

        return response;
    }

    @Tool(name = "get_graph_summary",
            description = "Returns a high-level summary of the entire analyzed codebase: total node/edge counts, " +
                    "breakdown by type (CONTROLLER, SERVICE, PROXY, MESSAGE_CONSUMER, etc.), and active bean count. " +
                    "Useful for getting a quick architectural overview before running targeted queries.")
    public Map<String, Object> getGraphSummary() {
        return activeEngine.get().getGraphSummary();
    }

    @Tool(name = "get_api_surface",
            description = "Lists every exposed HTTP endpoint in the application: HTTP verb, URL path, " +
                    "controller class, and handler method. Extracted from @GetMapping, @PostMapping, " +
                    "@PutMapping, @DeleteMapping, @PatchMapping, and @RequestMapping annotations. " +
                    "Returns endpoints sorted by path and verb for easy scanning.")
    public List<Map<String, Object>> getApiSurface() {
        return activeEngine.get().getApiSurface().stream()
                .map(ep -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("httpVerb", ep.httpVerb());
                    m.put("path", ep.path());
                    m.put("controllerClass", ep.controllerClass());
                    m.put("methodName", ep.methodName());
                    if (ep.produces() != null) m.put("produces", ep.produces());
                    if (ep.consumes() != null) m.put("consumes", ep.consumes());
                    return m;
                })
                .toList();
    }

    @Tool(name = "audit_git_provenance",
            description = "Audits the Git commit history for SSH signature compliance. " +
                    "Returns all provenance violations: commits that use forbidden GPG signatures, " +
                    "lack SSH signatures entirely, or have invalid/corrupt SSH signatures. " +
                    "Enforces strict SSH-only signing policies across the repository.")
    public Map<String, Object> auditGitProvenance() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            Path rootPath = Path.of(activeSourceRoot.get()).toAbsolutePath();
            // Walk up to find .git directory
            Path repoRoot = rootPath;
            while (repoRoot != null && !repoRoot.resolve(".git").toFile().exists()) {
                repoRoot = repoRoot.getParent();
            }

            if (repoRoot == null) {
                response.put("error", true);
                response.put("message", "No .git directory found in or above source root: " + rootPath);
                return response;
            }

            GitProvenanceChecker checker = new GitProvenanceChecker(repoRoot);
            try {
                List<ProvenanceViolation> violations = checker.auditHistory();

                List<Map<String, Object>> violationList = violations.stream()
                        .map(v -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("commitHash", v.commitHash().substring(0, 8));
                            m.put("message", v.message());
                            m.put("violationType", v.type().name());
                            return m;
                        })
                        .toList();

                response.put("repositoryPath", repoRoot.toString());
                response.put("totalViolations", violations.size());
                response.put("violations", violationList);
                response.put("compliant", violations.isEmpty());

                if (violations.isEmpty()) {
                    response.put("summary", "All commits have valid SSH signatures — fully compliant.");
                } else {
                    Map<String, Integer> breakdown = new LinkedHashMap<>();
                    for (ProvenanceViolation v : violations) {
                        breakdown.merge(v.type().name(), 1, Integer::sum);
                    }
                    response.put("violationBreakdown", breakdown);
                    response.put("summary", violations.size() + " violations found across "
                            + breakdown.size() + " categories.");
                }
            } finally {
                checker.close();
            }
        } catch (IOException e) {
            log.error("Failed to audit Git provenance", e);
            response.put("error", true);
            response.put("message", "Git provenance audit failed: " + e.getMessage());
        }

        return response;
    }

    @Tool(name = "refresh_analysis",
            description = "Triggers an incremental re-analysis of the target project, " +
                    "only re-processing files changed since the last analysis. " +
                    "Returns a diff report: added/modified/deleted file counts. " +
                    "Example: run this after pulling new code to update the architecture graph.")
    public Map<String, Object> refreshAnalysis() {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            Path sourceRoot = Path.of(activeSourceRoot.get()).toAbsolutePath();
            ChangeSet changes = activeEngine.get().analyzeIncremental(sourceRoot, Set.of());
            response.put("analyzed", true);
            response.put("added", changes.added().size());
            response.put("modified", changes.modified().size());
            response.put("deleted", changes.deleted().size());
            response.put("totalChanges", changes.totalChanges());
            if (!changes.hasChanges()) {
                response.put("message", "No changes detected — graph is up to date");
            }
        } catch (IOException e) {
            log.error("Incremental analysis failed", e);
            response.put("error", true);
            response.put("message", "Analysis failed: " + e.getMessage());
        }
        return response;
    }

    @Tool(name = "get_module_dependency_graph",
            description = "Returns the inter-module dependency graph for multi-module Maven/Gradle projects. " +
                    "Shows which modules depend on which, enabling architectural boundary analysis. " +
                    "Example: use this to verify your domain module doesn't depend on infrastructure.")
    public Map<String, Object> getModuleDependencyGraph() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> modules = graph.findNodesByType(NodeType.MODULE).stream()
                .map(node -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("artifactId", node.name());
                    m.put("nodeId", node.id());
                    List<String> dependsOn = graph.getOutgoingEdges(node.id()).stream()
                            .filter(e -> e.type() == EdgeType.DEPENDS_ON)
                            .map(e -> graph.getNode(e.targetId()).map(SpecterNode::name).orElse("unknown"))
                            .toList();
                    m.put("dependsOn", dependsOn);
                    return m;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalModules", modules.size());
        response.put("modules", modules);
        return response;
    }

    @Tool(name = "find_cross_module_violations",
            description = "Detects architectural violations where a lower-level module " +
                    "depends on a higher-level module (e.g., domain depending on infrastructure). " +
                    "Requires module layer annotations or naming conventions to be configured. " +
                    "Example: with 'web,service,domain,infra' layer order, domain→infra is a violation.")
    public Map<String, Object> findCrossModuleViolations(
            @ToolParam(description = "Layer ordering as CSV, outermost first: e.g., 'web,application,domain,infrastructure'")
            String layerOrder) {

        List<String> layers = List.of(layerOrder.split(","));
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> violations = new ArrayList<>();

        for (SpecterNode module : graph.findNodesByType(NodeType.MODULE)) {
            String moduleName = module.name().toLowerCase();
            int moduleLayer = findLayer(layers, moduleName);
            if (moduleLayer < 0) continue;

            for (SpecterEdge edge : graph.getOutgoingEdges(module.id())) {
                if (edge.type() != EdgeType.DEPENDS_ON) continue;
                graph.getNode(edge.targetId()).ifPresent(target -> {
                    String targetName = target.name().toLowerCase();
                    int targetLayer = findLayer(layers, targetName);
                    if (targetLayer >= 0 && targetLayer < moduleLayer) {
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("sourceModule", module.name());
                        v.put("sourceLayer", layers.get(moduleLayer));
                        v.put("targetModule", target.name());
                        v.put("targetLayer", layers.get(targetLayer));
                        v.put("violation", "Lower layer '" + layers.get(moduleLayer)
                                + "' depends on higher layer '" + layers.get(targetLayer) + "'");
                        violations.add(v);
                    }
                });
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("layerOrder", layerOrder);
        response.put("totalViolations", violations.size());
        response.put("violations", violations);
        return response;
    }

    @Tool(name = "audit_security_boundaries",
            description = "Scans all CONTROLLER_ENDPOINT nodes and identifies which have NO @PreAuthorize " +
                    "or security constraint — potential unauthenticated exposure. " +
                    "Returns a risk-scored list of unprotected endpoints. " +
                    "Example: find all endpoints your security team forgot to lock down.")
    public Map<String, Object> auditSecurityBoundaries() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> unprotected = new ArrayList<>();

        for (SpecterNode ep : graph.findNodesByType(NodeType.CONTROLLER_ENDPOINT)) {
            boolean hasSecurity = graph.getIncomingEdges(ep.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.SECURED_BY);
            if (!hasSecurity && graph.getOutgoingEdges(ep.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.SECURED_BY)) {
                hasSecurity = true;
            }
            if (!hasSecurity) {
                String httpVerb = ep.metadata().getOrDefault("httpVerb", "REQUEST");
                String path = ep.metadata().getOrDefault("path", "/");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("endpointId", ep.id());
                m.put("httpVerb", httpVerb);
                m.put("path", path);
                m.put("riskLevel", "POST PUT DELETE PATCH".contains(httpVerb.toUpperCase()) ? "CRITICAL" : "HIGH");
                unprotected.add(m);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalEndpoints", graph.findNodesByType(NodeType.CONTROLLER_ENDPOINT).size());
        response.put("unprotectedCount", unprotected.size());
        response.put("unprotectedEndpoints", unprotected);
        return response;
    }

    @Tool(name = "get_security_filter_chain",
            description = "Returns the complete Spring Security filter chain configuration: " +
                    "CSRF state, session policy, URL matcher rules, and which roles/expressions protect each path. " +
                    "Example: inspect your security config to understand what's actually protected.")
    public Map<String, Object> getSecurityFilterChain() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> chains = graph.findNodesByType(NodeType.SECURITY_FILTER_CHAIN).stream()
                .map(node -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", node.id());
                    m.put("name", node.name());
                    List<Map<String, Object>> flags = graph.getOutgoingEdges(node.id()).stream()
                            .filter(e -> e.type() == EdgeType.CONFIGURED_BY)
                            .map(e -> graph.getNode(e.targetId())
                                    .map(target -> {
                                        Map<String, Object> fm = new LinkedHashMap<>();
                                        fm.put("id", target.id());
                                        fm.put("flag", target.metadata().get("flag"));
                                        fm.put("rule", target.metadata().get("rule"));
                                        return fm;
                                    }).orElse(null))
                            .filter(Objects::nonNull)
                            .toList();
                    m.put("securityFlags", flags);
                    return m;
                })
                .toList();

        List<Map<String, Object>> authRules = graph.findNodesByType(NodeType.AUTH_RULE).stream()
                .map(node -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", node.id());
                    m.put("name", node.name());
                    m.put("expression", node.metadata().get("expression"));
                    m.put("annotation", node.metadata().get("annotation"));
                    return m;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filterChains", chains);
        response.put("authRules", authRules);
        return response;
    }

    @Tool(name = "trace_config_property",
            description = "Given a property key (e.g., 'spring.datasource.url'), traces which beans " +
                    "consume it via @Value or @ConfigurationProperties, and which properties files define it. " +
                    "Identifies undefined properties that will cause startup failures. " +
                    "Example: trace 'server.port' to find every Bean that depends on it.")
    public Map<String, Object> traceConfigProperty(
            @ToolParam(description = "Property key to trace, e.g. 'app.feature.enabled'") String propertyKey) {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> consumers = new ArrayList<>();
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (SpecterNode node : graph.findNodesByType(NodeType.CONFIG_KEY)) {
            String key = node.metadata().get("propertyKey");
            if (!propertyKey.equals(key)) continue;

            for (SpecterEdge edge : graph.getIncomingEdges(node.id())) {
                if (edge.type() == EdgeType.USES_CONFIG) {
                    graph.getNode(edge.sourceId()).ifPresent(source -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", source.id());
                        m.put("name", source.name());
                        m.put("definedIn", node.metadata().get("definedIn"));
                        m.put("hasDefault", node.metadata().get("hasDefault"));
                        consumers.add(m);
                    });
                }
            }

            for (SpecterEdge edge : graph.getOutgoingEdges(node.id())) {
                if (edge.type() == EdgeType.RESOLVES_TO) {
                    graph.getNode(edge.targetId()).ifPresent(target -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", target.name());
                        m.put("value", target.metadata().get("value"));
                        definitions.add(m);
                    });
                }
            }
        }

        boolean isUndefined = consumers.stream()
                .anyMatch(m -> "false".equals(m.get("hasDefault"))) && definitions.isEmpty();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("propertyKey", propertyKey);
        response.put("consumers", consumers);
        response.put("totalConsumers", consumers.size());
        response.put("definitions", definitions);
        response.put("totalDefinitions", definitions.size());
        response.put("undefined", isUndefined);
        if (isUndefined) {
            response.put("message", "WARNING: " + propertyKey + " is referenced in code but has no default value and no properties file entry — this WILL cause a startup failure");
        }
        return response;
    }

    @Tool(name = "audit_undefined_properties",
            description = "Scans all @Value and @ConfigurationProperties usages and returns all " +
                    "property keys referenced in code that have no default value and no entry " +
                    "in any properties/YAML file — guaranteed startup failures. " +
                    "Example: find all properties your app needs but hasn't defined.")
    public List<Map<String, Object>> auditUndefinedProperties() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> undefined = new ArrayList<>();

        for (SpecterNode keyNode : graph.findNodesByType(NodeType.CONFIG_KEY)) {
            boolean hasDefault = "true".equals(keyNode.metadata().get("hasDefault"));
            boolean hasValue = graph.getOutgoingEdges(keyNode.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.RESOLVES_TO);

            if (!hasDefault && !hasValue) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("propertyKey", keyNode.metadata().get("propertyKey"));
                m.put("definedIn", keyNode.metadata().get("definedIn"));
                m.put("hasDefault", false);
                m.put("hasPropertyFileEntry", false);
                m.put("risk", "STARTUP_FAILURE");
                undefined.add(m);
            }
        }

        return undefined;
    }

    @Tool(name = "find_undocumented_endpoints",
            description = "Compares discovered CONTROLLER_ENDPOINT nodes against the OpenAPI spec " +
                    "and returns all endpoints that exist in code but have no @Operation annotation " +
                    "or spec entry — contract gaps that will break API consumers. " +
                    "Example: find every endpoint your OpenAPI spec is missing.")
    public List<Map<String, Object>> findUndocumentedEndpoints() {
        var graph = activeEngine.get().getGraph();
        return graph.findNodesByType(NodeType.CONTROLLER_ENDPOINT).stream()
                .filter(ep -> !"true".equals(ep.metadata().get("hasOpenApiAnnotation"))
                        && !"true".equals(ep.metadata().get("hasOpenApiSpec")))
                .map(ep -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("endpointId", ep.id());
                    m.put("path", ep.metadata().get("path"));
                    m.put("httpVerb", ep.metadata().get("httpVerb"));
                    m.put("controllerClass", ep.metadata().get("controllerClass"));
                    m.put("methodName", ep.metadata().get("methodName"));
                    return m;
                })
                .toList();
    }

    @Tool(name = "get_dto_lineage",
            description = "For a given DTO class name, traces the full data lineage: " +
                    "which endpoints accept/return it, which entities it maps to/from, " +
                    "and which converters transform it. " +
                    "Example: trace 'UserDto' to see all APIs and entities connected to it.")
    public Map<String, Object> getDtoLineage(
            @ToolParam(description = "Simple or fully qualified DTO class name") String dtoClassName) {
        var graph = activeEngine.get().getGraph();
        String dtoId = graph.findNodeByName(dtoClassName).map(SpecterNode::id)
                .orElse("dto:" + dtoClassName);

        List<Map<String, Object>> endpoints = new ArrayList<>();
        for (SpecterEdge edge : graph.getIncomingEdges(dtoId)) {
            if (edge.type() == EdgeType.ACCEPTS || edge.type() == EdgeType.RETURNS) {
                graph.getNode(edge.sourceId()).ifPresent(ep -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("endpointId", ep.id());
                    m.put("path", ep.metadata().get("path"));
                    m.put("httpVerb", ep.metadata().get("httpVerb"));
                    m.put("direction", edge.type() == EdgeType.ACCEPTS ? "request" : "response");
                    endpoints.add(m);
                });
            }
        }

        List<Map<String, Object>> entities = new ArrayList<>();
        for (SpecterEdge edge : graph.getOutgoingEdges(dtoId)) {
            if (edge.type() == EdgeType.MAPS_TO) {
                graph.getNode(edge.targetId()).ifPresent(entity -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("entityId", entity.id());
                    m.put("entityName", entity.name());
                    entities.add(m);
                });
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dtoName", dtoClassName);
        response.put("endpoints", endpoints);
        response.put("entities", entities);
        return response;
    }

    @Tool(name = "get_service_topology",
            description = "Returns the complete microservice call topology: which external services " +
                    "this application calls, via what mechanism (Feign, WebClient, RestTemplate), " +
                    "and which internal components initiate those calls. " +
                    "Example: map all inter-service communication in your distributed system.")
    public Map<String, Object> getServiceTopology() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> externalServices = graph.findNodesByType(NodeType.EXTERNAL_SERVICE).stream()
                .map(service -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("serviceId", service.id());
                    m.put("serviceName", service.name());
                    m.put("protocol", service.metadata().get("protocol"));
                    List<Map<String, Object>> callers = graph.getIncomingEdges(service.id()).stream()
                            .filter(e -> e.type() == EdgeType.CALLS_REMOTE)
                            .map(e -> graph.getNode(e.sourceId())
                                    .map(src -> {
                                        Map<String, Object> cm = new LinkedHashMap<>();
                                        cm.put("callerId", src.id());
                                        cm.put("callerName", src.name());
                                        cm.put("callerType", src.type().name());
                                        return cm;
                                    }).orElse(null))
                            .filter(Objects::nonNull)
                            .toList();
                    m.put("calledBy", callers);
                    return m;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalExternalServices", externalServices.size());
        response.put("externalServices", externalServices);
        return response;
    }

    @Tool(name = "find_resilience_gaps",
            description = "Identifies outbound service calls (CALLS_REMOTE edges) that have no " +
                    "@CircuitBreaker, @Retry, @Bulkhead, or @TimeLimiter annotation — " +
                    "single points of failure in the distributed call graph. " +
                    "Example: find every remote call that lacks a circuit breaker.")
    public List<Map<String, Object>> findResilienceGaps() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> gaps = new ArrayList<>();

        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() != EdgeType.CALLS_REMOTE) continue;
            graph.getNode(edge.sourceId()).ifPresent(source -> {
                boolean hasResilience = graph.getOutgoingEdges(source.id()).stream()
                        .anyMatch(e -> e.type() == EdgeType.CALLS && graph.getNode(e.targetId())
                                .map(n -> n.type() == NodeType.PROXY
                                        && n.metadata().getOrDefault("PROXY_STEREOTYPE", "")
                                                .contains("RETRY"))
                                .orElse(false));
                if (!hasResilience) {
                    graph.getNode(edge.targetId()).ifPresent(target -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("callerId", source.id());
                        m.put("callerName", source.name());
                        m.put("externalService", target.name());
                        m.put("risk", "No resilience annotations found");
                        gaps.add(m);
                    });
                }
            });
        }
        return gaps;
    }

    @Tool(name = "get_test_coverage_map",
            description = "Returns which production components have test coverage (TESTS edges) " +
                    "vs. which are only mocked in other tests (MOCKS edges, which is NOT coverage). " +
                    "Identifies completely untested components — high-risk refactoring targets. " +
                    "Example: see which services have actual test coverage vs just being mocked.")
    public Map<String, Object> getTestCoverageMap() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> tested = new ArrayList<>();
        List<Map<String, Object>> mockedOnly = new ArrayList<>();
        Set<String> allProductionIds = new HashSet<>();

        for (SpecterNode node : graph.allNodes()) {
            NodeType type = node.type();
            if (type == NodeType.TEST || type == NodeType.PROXY || type == NodeType.MODULE) continue;
            allProductionIds.add(node.id());
            boolean hasTest = graph.getIncomingEdges(node.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.TESTS);
            boolean hasMock = graph.getIncomingEdges(node.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.MOCKS);
            if (hasTest) {
                tested.add(nodeToSummary(node));
            } else if (hasMock) {
                mockedOnly.add(nodeToSummary(node));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalProductionComponents", allProductionIds.size());
        response.put("testedCount", tested.size());
        response.put("mockedOnlyCount", mockedOnly.size());
        response.put("untestedCount", allProductionIds.size() - tested.size() - mockedOnly.size());
        response.put("tested", tested);
        response.put("mockedOnly", mockedOnly);
        return response;
    }

    @Tool(name = "find_untested_components",
            description = "Returns all SERVICE, REPOSITORY, and COMPONENT nodes that have " +
                    "zero incoming TESTS edges — no test class directly exercises them. " +
                    "Example: find every production component with no unit/integration tests.")
    public List<Map<String, Object>> findUntestedComponents() {
        var graph = activeEngine.get().getGraph();
        return graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.SERVICE
                        || n.type() == NodeType.REPOSITORY
                        || n.type() == NodeType.COMPONENT)
                .filter(n -> graph.getIncomingEdges(n.id()).stream()
                        .noneMatch(e -> e.type() == EdgeType.TESTS))
                .map(this::nodeToSummary)
                .toList();
    }

    @Tool(name = "register_project",
            description = "Registers a new project for analysis by Specter. " +
                    "Triggers async incremental analysis if a cached graph exists, " +
                    "or full analysis if not. Returns a projectId for subsequent tool calls. " +
                    "Example: register multiple microservices to analyze them side-by-side.")
    public Map<String, Object> registerProject(
            @ToolParam(description = "Absolute path to the project source root") String sourceRoot,
            @ToolParam(description = "Optional display name for this project") String displayName) {
        try {
            ProjectContext ctx = projectRegistry.registerProject(sourceRoot, displayName);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("projectId", ctx.projectId());
            response.put("displayName", ctx.displayName());
            response.put("sourceRoot", ctx.sourceRoot().toString());
            response.put("status", ctx.status().name());
            return response;
        } catch (IOException e) {
            return Map.of("error", true, "message", e.getMessage());
        }
    }

    @Tool(name = "list_projects",
            description = "Lists all registered projects with their analysis status, " +
                    "node/edge counts, and last analysis timestamp. " +
                    "Example: see all analyzed microservices at a glance.")
    public List<Map<String, Object>> listProjects() {
        return projectRegistry.listProjects().stream()
                .map(ctx -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("projectId", ctx.projectId());
                    m.put("displayName", ctx.displayName());
                    m.put("sourceRoot", ctx.sourceRoot().toString());
                    m.put("status", ctx.status().name());
                    if (ctx.engine() != null) {
                        m.put("nodeCount", ctx.engine().getGraph().nodeCount());
                        m.put("edgeCount", ctx.engine().getGraph().edgeCount());
                    }
                    return m;
                })
                .toList();
    }

    @Tool(name = "switch_project",
            description = "Switches the active project context. Subsequent tool calls " +
                    "will operate against this project's graph. " +
                    "Example: switch between 'user-service' and 'order-service' for queries.")
    public Map<String, Object> switchProject(
            @ToolParam(description = "Project ID returned by register_project") String projectId) {
        ProjectContext ctx = projectRegistry.getProject(projectId);
        Map<String, Object> response = new LinkedHashMap<>();
        if (ctx == null || ctx.status() != ProjectRegistry.AnalysisStatus.READY) {
            response.put("error", true);
            response.put("message", "Project not found or not ready: " + projectId);
        } else {
            activeEngine.set(ctx.engine());
            activeSourceRoot.set(ctx.sourceRoot().toString());  // update active source root
            response.put("projectId", ctx.projectId());
            response.put("displayName", ctx.displayName());
            response.put("status", ctx.status().name());
            response.put("switched", true);
        }
        return response;
    }

    @Tool(name = "get_architectural_health",
            description = "Computes a comprehensive architectural health score (0-100) across 7 dimensions: " +
                    "dependency health, security, resilience, test coverage, observability, API contract quality, " +
                    "and architecture rule compliance. Custom rules added via add_custom_rule are not factored " +
                    "into the health score — only standard rules contribute. " +
                    "Returns dimension scores, critical issues, and prioritized remediation recommendations.")
    public Map<String, Object> getArchitecturalHealth() {
        ArchitecturalHealthAnalyzer analyzer = new ArchitecturalHealthAnalyzer(activeEngine.get().getGraph());
        var report = analyzer.analyze();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("overallScore", report.overallScore());
        response.put("grade", gradeScore(report.overallScore()));

        List<Map<String, Object>> dimList = new ArrayList<>();
        for (var entry : report.dimensions().entrySet()) {
            Map<String, Object> dim = new LinkedHashMap<>();
            dim.put("dimension", entry.getKey());
            dim.put("score", entry.getValue().score());
            dim.put("issues", entry.getValue().criticalIssues().size());
            dimList.add(dim);
        }
        response.put("dimensions", dimList);

        List<Map<String, Object>> issueList = report.criticalIssues().stream()
                .map(issue -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", issue.nodeId());
                    m.put("reason", issue.reason());
                    m.put("score", issue.score());
                    m.put("severity", issue.level().name());
                    return m;
                })
                .toList();
        response.put("criticalIssues", issueList);
        response.put("recommendations", report.recommendations());
        return response;
    }

    @Tool(name = "get_observability_map",
          description = "Returns all METRIC, TRACE_SPAN, and HEALTH_INDICATOR nodes mapped to " +
              "the production components that emit them. Shows which services are instrumented " +
              "and which are invisible to your monitoring stack. " +
              "Example: find every service missing Micrometer metrics before a production incident.")
    public Map<String, Object> getObservabilityMap() {
        var graph = activeEngine.get().getGraph();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> metrics = new ArrayList<>();
        for (SpecterNode node : graph.findNodesByType(NodeType.METRIC)) {
            Map<String, Object> metric = nodeToMap(node);
            List<String> measuredBy = new ArrayList<>();
            for (SpecterEdge edge : graph.getIncomingEdges(node.id())) {
                if (edge.type() == EdgeType.MEASURES) {
                    graph.getNode(edge.sourceId()).ifPresent(src -> measuredBy.add(src.name()));
                }
            }
            metric.put("measuredByClasses", measuredBy);
            metrics.add(metric);
        }
        result.put("metrics", metrics);

        List<Map<String, Object>> traceSpans = new ArrayList<>();
        for (SpecterNode node : graph.findNodesByType(NodeType.TRACE_SPAN)) {
            Map<String, Object> span = nodeToMap(node);
            List<String> tracedBy = new ArrayList<>();
            for (SpecterEdge edge : graph.getIncomingEdges(node.id())) {
                if (edge.type() == EdgeType.TRACES) {
                    graph.getNode(edge.sourceId()).ifPresent(src -> tracedBy.add(src.name()));
                }
            }
            span.put("tracedByClasses", tracedBy);
            traceSpans.add(span);
        }
        result.put("traceSpans", traceSpans);

        List<Map<String, Object>> healthIndicators = new ArrayList<>();
        for (SpecterNode node : graph.findNodesByType(NodeType.HEALTH_INDICATOR)) {
            healthIndicators.add(nodeToMap(node));
        }
        result.put("healthIndicators", healthIndicators);

        int instrumentedCount = 0;
        int totalServices = 0;
        for (SpecterNode node : graph.allNodes()) {
            if (node.type() != NodeType.SERVICE && node.type() != NodeType.CONTROLLER) continue;
            totalServices++;
            if ("true".equals(node.metadata().get("instrumented"))) instrumentedCount++;
        }
        result.put("totalInstrumentedServices", instrumentedCount);
        result.put("totalServiceControllerNodes", totalServices);

        return result;
    }

    @Tool(name = "find_uninstrumented_services",
          description = "Returns all SERVICE and CONTROLLER nodes that have no @Timed, " +
              "no MeterRegistry injection, and no distributed tracing annotations — " +
              "completely dark in production monitoring.")
    public List<Map<String, Object>> findUninstrumentedServices() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> darkServices = new ArrayList<>();

        for (SpecterNode node : graph.allNodes()) {
            if (node.type() != NodeType.SERVICE && node.type() != NodeType.CONTROLLER) continue;

            boolean instrumented = "true".equals(node.metadata().get("instrumented"));
            boolean hasMeterRegistry = "true".equals(node.metadata().get("hasMeterRegistry"));
            boolean hasTracer = "true".equals(node.metadata().get("hasTracerInjection"));

            boolean hasMeasures = graph.getOutgoingEdges(node.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.MEASURES);
            boolean hasTraces = graph.getOutgoingEdges(node.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.TRACES);

            if (!instrumented && !hasMeterRegistry && !hasTracer && !hasMeasures && !hasTraces) {
                Map<String, Object> dark = new LinkedHashMap<>();
                dark.put("nodeId", node.id());
                dark.put("name", node.name());
                dark.put("type", node.type().name());
                dark.put("instrumented", String.valueOf(instrumented));
                dark.put("hasMeterRegistry", String.valueOf(hasMeterRegistry));
                dark.put("hasTracerInjection", String.valueOf(hasTracer));
                darkServices.add(dark);
            }
        }

        return darkServices;
    }

    @Tool(name = "evaluate_architecture_rules",
          description = "Evaluates built-in and custom architectural rules against the graph. " +
              "Detects layer violations (controller→repository direct injection), messaging misuse, " +
              "and security boundary breaches. Returns violations with severity and rationale. " +
              "Example: enforce clean architecture boundaries automatically on every PR.")
    public Map<String, Object> evaluateArchitectureRules() {
        var violations = ruleEngine.evaluate();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalViolations", violations.size());

        long errors = violations.stream().filter(v -> "ERROR".equals(v.rule().severity())).count();
        long warnings = violations.stream().filter(v -> "WARNING".equals(v.rule().severity())).count();
        result.put("errorCount", errors);
        result.put("warningCount", warnings);

        List<Map<String, Object>> violationList = new ArrayList<>();
        for (var v : violations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", v.rule().ruleId());
            m.put("description", v.rule().description());
            m.put("severity", v.rule().severity());
            m.put("rationale", v.rule().rationale());
            m.put("sourceNode", v.source().name());
            m.put("sourceType", v.source().type().name());
            m.put("targetNode", v.target().name());
            m.put("targetType", v.target().type().name());
            m.put("edgeType", v.edge().type().name());
            violationList.add(m);
        }
        result.put("violations", violationList);

        return result;
    }

    @Tool(name = "add_custom_rule",
          description = "Adds a custom architectural rule to the rule engine. " +
              "Rules are edge-type constraints between node types. " +
              "Example: forbid INJECTS edges from SERVICE nodes to EXTERNAL_SERVICE nodes " +
              "to ensure all service calls go through a gateway layer.")
    public Map<String, Object> addCustomRule(
            @ToolParam(description = "Rule ID (e.g., 'ARCH-006')") String ruleId,
            @ToolParam(description = "Human-readable rule description") String description,
            @ToolParam(description = "Severity: ERROR, WARNING, or INFO") String severity,
            @ToolParam(description = "Forbidden edge type (e.g., 'INJECTS', 'CALLS_REMOTE')") String forbiddenEdge,
            @ToolParam(description = "Source node type (e.g., 'CONTROLLER', 'SERVICE')") String sourceType,
            @ToolParam(description = "Target node type (e.g., 'REPOSITORY', 'EXTERNAL_SERVICE')") String targetType,
            @ToolParam(description = "Rationale explaining why this is forbidden") String rationale) {

        EdgeType edgeType;
        try {
            edgeType = EdgeType.valueOf(forbiddenEdge);
        } catch (IllegalArgumentException e) {
            return Map.of("error", true, "message", "Unknown edge type: " + forbiddenEdge);
        }

        NodeType srcType;
        try {
            srcType = NodeType.valueOf(sourceType);
        } catch (IllegalArgumentException e) {
            return Map.of("error", true, "message", "Unknown node type: " + sourceType);
        }

        NodeType tgtType;
        try {
            tgtType = NodeType.valueOf(targetType);
        } catch (IllegalArgumentException e) {
            return Map.of("error", true, "message", "Unknown node type: " + targetType);
        }

        var rule = new ArchitectureRule(
                ruleId, description, severity, edgeType, srcType, tgtType, rationale);

        ruleEngine.addCustomRule(rule);

        return Map.of("added", true, "ruleId", ruleId, "description", description);
    }

    @Tool(name = "take_snapshot",
          description = "Captures the current graph state as a named snapshot for future comparison. " +
              "Use before a refactoring or before merging a branch. " +
              "Example: 'take_snapshot before-auth-refactor' then diff after changes.")
    public Map<String, Object> takeSnapshot(
            @ToolParam(description = "Label for this snapshot (e.g., 'before-refactor', 'main-branch')") String label) {
        Optional<Path> projectRoot = resolveProjectRoot();
        if (projectRoot.isEmpty()) {
            return Map.of("error", true, "message",
                    "Could not locate project root (no pom.xml/build.gradle found above source root)");
        }
        try {
            var graph = activeEngine.get().getGraph();
            GraphDiff.GraphSnapshot snapshot = activeEngine.get().snapshot(label);

            Path snapshotsDir = projectRoot.get().resolve(".specter-cache/snapshots");
            Files.createDirectories(snapshotsDir);
            Path snapshotFile = snapshotsDir.resolve(label + ".json");

            // saveSnapshot embeds label + capturedAt in JSON for accurate timestamp recovery
            GraphSerializer.saveSnapshot(graph, label, snapshotFile);

            return Map.of("saved", true, "label", label,
                    "nodeCount", snapshot.nodes().size(),
                    "edgeCount", snapshot.edges().size(),
                    "capturedAt", snapshot.capturedAt().toString(),
                    "path", snapshotFile.toString());
        } catch (IOException e) {
            log.warn("Failed to save snapshot '{}'", label, e);
            return Map.of("error", true, "message", "Failed to save snapshot: " + e.getMessage());
        }
    }

    @Tool(name = "diff_snapshots",
          description = "Compares two named snapshots and returns what changed: " +
              "added/removed/modified nodes and edges, plus the combined blast radius " +
              "of all changes — which other components are impacted by the diff. " +
              "Example: diff 'before-refactor' vs 'after-refactor' to validate safety.")
    public Map<String, Object> diffSnapshots(
            @ToolParam(description = "Label of the 'before' snapshot") String beforeLabel,
            @ToolParam(description = "Label of the 'after' snapshot (or 'current' for live graph)") String afterLabel) {
        Optional<Path> projectRoot = resolveProjectRoot();
        if (projectRoot.isEmpty()) {
            return Map.of("error", true, "message",
                    "Could not locate project root (no pom.xml/build.gradle found above source root)");
        }
        try {
            Path snapshotsDir = projectRoot.get().resolve(".specter-cache/snapshots");
            var liveGraph = activeEngine.get().getGraph();

            GraphDiff.GraphSnapshot before = loadSnapshot(snapshotsDir, beforeLabel);
            if (before == null) return Map.of("error", true,
                    "message", "Snapshot not found: " + beforeLabel);

            GraphDiff.GraphSnapshot after;
            if ("current".equals(afterLabel)) {
                after = activeEngine.get().snapshot("current");
            } else {
                after = loadSnapshot(snapshotsDir, afterLabel);
                if (after == null) return Map.of("error", true,
                        "message", "Snapshot not found: " + afterLabel);
            }

            GraphDiff.DiffResult diff = GraphDiff.diff(before, after, liveGraph);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("beforeLabel", beforeLabel);
            result.put("afterLabel", afterLabel);
            result.put("addedNodes", diff.addedNodes().size());
            result.put("removedNodes", diff.removedNodes().size());
            result.put("changedNodes", diff.changedNodes().size());
            result.put("addedEdges", diff.addedEdges().size());
            result.put("removedEdges", diff.removedEdges().size());
            result.put("hasChanges", diff.hasChanges());

            List<Map<String, Object>> impactedList = new ArrayList<>();
            for (var impact : diff.impactedByChanges()) {
                impactedList.add(Map.of("nodeId", impact.nodeId(),
                        "name", impact.name(), "type", impact.type().name(),
                        "reason", impact.reason()));
            }
            result.put("impactedByChanges", impactedList);

            return result;
        } catch (IOException e) {
            log.warn("Failed to diff snapshots", e);
            return Map.of("error", true, "message", "Failed to diff snapshots: " + e.getMessage());
        }
    }

    @Tool(name = "list_snapshots",
          description = "Lists all available graph snapshots with their labels and capture timestamps.")
    public List<Map<String, Object>> listSnapshots() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        Optional<Path> projectRoot = resolveProjectRoot();
        if (projectRoot.isEmpty()) return snapshots;
        try {
            Path snapshotsDir = projectRoot.get().resolve(".specter-cache/snapshots");
            if (!Files.exists(snapshotsDir)) return snapshots;

            try (var files = Files.list(snapshotsDir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .sorted((a, b) -> {
                            try {
                                return Files.getLastModifiedTime(b).compareTo(
                                        Files.getLastModifiedTime(a));
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .forEach(file -> {
                            String snapshotLabel = file.getFileName().toString().replace(".json", "");
                            try {
                                var graph = GraphSerializer.loadFromFile(file);
                                GraphSerializer.SnapshotMetadata meta =
                                        GraphSerializer.loadSnapshotMetadata(file);
                                snapshots.add(Map.of(
                                        "label", snapshotLabel,
                                        "nodeCount", graph.nodeCount(),
                                        "edgeCount", graph.edgeCount(),
                                        "capturedAt", meta != null
                                                ? meta.capturedAt().toString()
                                                : Files.getLastModifiedTime(file).toString()
                                ));
                            } catch (IOException e) {
                                snapshots.add(Map.of("label", snapshotLabel, "error", true));
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("Failed to list snapshots", e);
        }
        return snapshots;
    }

    private GraphDiff.GraphSnapshot loadSnapshot(Path snapshotsDir, String label) throws IOException {
        Path file = snapshotsDir.resolve(label + ".json");
        if (!Files.exists(file)) return null;
        var graph = GraphSerializer.loadFromFile(file);
        // Recover actual capture timestamp from embedded metadata
        GraphSerializer.SnapshotMetadata meta = GraphSerializer.loadSnapshotMetadata(file);
        Instant capturedAt = (meta != null)
                ? meta.capturedAt()
                : Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis());
        return new GraphDiff.GraphSnapshot(
                graph.allNodes().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.specter.core.graph.SpecterNode::id, n -> n,
                                (a, b) -> b)),  // merge on duplicate IDs — last wins
                Set.copyOf(graph.allEdges()),
                capturedAt,
                label
        );
    }

    @Tool(name = "find_performance_antipatterns",
          description = "Scans the dependency graph for common JPA/Spring performance anti-patterns: " +
              "potential N+1 query hotspots, missing @Transactional on multi-step operations, " +
              "eager-loaded @OneToMany relationships, and missing @Cacheable on hot read paths. " +
              "Example: 'find all N+1 query risks before the load test'")
    public List<Map<String, Object>> findPerformanceAntipatterns() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> antipatterns = new ArrayList<>();

        for (SpecterNode node : graph.findNodesByType(NodeType.PERFORMANCE_ISSUE)) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("nodeId", node.id());
            issue.put("description", node.name());
            issue.put("antiPattern", node.metadata().get("antiPattern"));
            issue.put("severity", node.metadata().get("severity"));
            issue.put("className", node.metadata().get("className"));
            issue.put("methodName", node.metadata().get("methodName"));
            issue.put("details", node.metadata());
            antipatterns.add(issue);
        }

        for (SpecterNode node : graph.findNodesByType(NodeType.PERFORMANCE_HINT)) {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("nodeId", node.id());
            hint.put("description", node.name());
            hint.put("antiPattern", node.metadata().get("antiPattern"));
            hint.put("severity", node.metadata().get("severity"));
            hint.put("className", node.metadata().get("className"));
            hint.put("callCount", node.metadata().get("callCount"));
            hint.put("details", node.metadata());
            antipatterns.add(hint);
        }

        return antipatterns;
    }

    @Tool(name = "analyze_transaction_scope",
          description = "For a given service class, maps the full transaction scope: " +
              "which methods open transactions, which are called inside those transactions, " +
              "and whether any CALLS_REMOTE edges exist inside a @Transactional boundary " +
              "(distributed transaction anti-pattern).")
    public Map<String, Object> analyzeTransactionScope(
            @ToolParam(description = "Service class name to analyze") String className) {
        var graph = activeEngine.get().getGraph();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", className);

        String classNodeId = "class:" + className;
        SpecterNode classNode = graph.getNode(classNodeId).orElse(null);
        if (classNode == null) {
            classNode = graph.findNodeByName(className).orElse(null);
            if (classNode == null) {
                result.put("error", "Class not found in graph: " + className);
                return result;
            }
            classNodeId = classNode.id();
        }

        // Find proxy nodes that wrap this class
        List<SpecterNode> wrappingProxies = new ArrayList<>();
        boolean isTransactional = false;
        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() == EdgeType.CALLS && edge.targetId().equals(classNodeId)) {
                graph.getNode(edge.sourceId()).ifPresent(source -> {
                    if (source.type() == NodeType.PROXY) {
                        wrappingProxies.add(source);
                    }
                });
            }
        }
        for (SpecterNode proxy : wrappingProxies) {
            String stereotype = proxy.metadata().getOrDefault("PROXY_STEREOTYPE", "");
            if (stereotype.contains("TRANSACTION_INTERCEPTOR")) {
                isTransactional = true;
                break;
            }
        }

        result.put("isTransactional", isTransactional);

        // BFS traversal to map transaction scope
        List<Map<String, Object>> calledServices = new ArrayList<>();
        List<Map<String, Object>> calledRepositories = new ArrayList<>();
        List<Map<String, Object>> remoteCalls = new ArrayList<>();
        List<Map<String, Object>> exceptionTypes = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        visited.add(classNodeId);
        queue.add(classNodeId);

        while (!queue.isEmpty()) {
            String currentId = queue.poll();

            for (SpecterEdge edge : graph.getOutgoingEdges(currentId)) {
                SpecterNode targetNode = graph.getNode(edge.targetId()).orElse(null);
                if (targetNode == null) continue;

                if (edge.type() == EdgeType.CALLS) {
                    Map<String, Object> callInfo = new LinkedHashMap<>();
                    callInfo.put("name", targetNode.name());
                    callInfo.put("id", targetNode.id());

                    if (targetNode.type() == NodeType.SERVICE) {
                        calledServices.add(callInfo);
                        if (visited.add(edge.targetId())) {
                            queue.add(edge.targetId());
                        }
                    } else if (targetNode.type() == NodeType.REPOSITORY
                            || targetNode.type() == NodeType.DATA_REPOSITORY) {
                        calledRepositories.add(callInfo);
                    } else if (targetNode.type() == NodeType.PROXY) {
                        if (visited.add(edge.targetId())) {
                            queue.add(edge.targetId());
                        }
                    }

                } else if (edge.type() == EdgeType.CALLS_REMOTE) {
                    Map<String, Object> remoteInfo = new LinkedHashMap<>();
                    remoteInfo.put("name", targetNode.name());
                    remoteInfo.put("id", targetNode.id());
                    remoteInfo.put("protocol",
                            targetNode.metadata().getOrDefault("protocol", "unknown"));
                    remoteCalls.add(remoteInfo);

                } else if (edge.type() == EdgeType.THROWS) {
                    Map<String, Object> exInfo = new LinkedHashMap<>();
                    exInfo.put("exceptionType", targetNode.name());
                    exInfo.put("id", targetNode.id());
                    exceptionTypes.add(exInfo);
                }
            }
        }

        result.put("calledServices", calledServices);
        result.put("calledRepositories", calledRepositories);
        result.put("remoteCalls", remoteCalls);
        result.put("exceptionTypes", exceptionTypes);

        boolean distributedTransactionRisk = isTransactional && !remoteCalls.isEmpty();
        result.put("distributedTransactionRisk", distributedTransactionRisk);

        if (distributedTransactionRisk) {
            result.put("riskDetail", "Remote calls detected inside @Transactional scope — "
                    + "risk of distributed transaction inconsistency");
        }

        result.put("hasPerformanceIssues",
                graph.findNodesByType(NodeType.PERFORMANCE_ISSUE).stream()
                        .anyMatch(n -> className.equals(n.metadata().get("className"))));

        return result;
    }

    @Tool(name = "correlate_entity_schema",
          description = "Correlates JPA entities with their actual database schema definitions " +
              "from Flyway/Liquibase migrations. Finds mismatches: entities with no table, " +
              "fields with no column, or tables with no corresponding entity. " +
              "Example: detect if a migration was applied without updating the entity.")
    public Map<String, Object> correlateEntitySchema() {
        var graph = activeEngine.get().getGraph();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> mapped = new ArrayList<>();
        List<Map<String, Object>> unmappedEntities = new ArrayList<>();
        List<Map<String, Object>> unmappedTables = new ArrayList<>();

        Set<String> entityWithSchema = new HashSet<>();
        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() == EdgeType.SCHEMA_MAPS_TO) {
                graph.getNode(edge.sourceId()).ifPresent(source ->
                        graph.getNode(edge.targetId()).ifPresent(target ->
                                mapped.add(Map.of("entity", source.name(),
                                        "entityId", source.id(),
                                        "table", target.name(),
                                        "tableId", target.id(),
                                        "schemaMissing",
                                        "true".equals(source.metadata().get("schemaMissing"))))
                        ));
                entityWithSchema.add(edge.sourceId());
            }
        }

        for (SpecterNode node : graph.allNodes()) {
            if (node.type() == NodeType.PERSISTENCE_ENTITY && !entityWithSchema.contains(node.id())) {
                unmappedEntities.add(Map.of("entityName", node.name(), "entityId", node.id(),
                        "schemaMissing", true));
            }
        }

        for (SpecterNode node : graph.findNodesByType(NodeType.SCHEMA_TABLE)) {
            boolean hasEntity = graph.getIncomingEdges(node.id()).stream()
                    .anyMatch(e -> e.type() == EdgeType.SCHEMA_MAPS_TO);
            if (!hasEntity) {
                unmappedTables.add(Map.of("tableName", node.name(), "tableId", node.id()));
            }
        }

        result.put("mapped", mapped);
        result.put("unmappedEntities", unmappedEntities);
        result.put("unmappedTables", unmappedTables);
        result.put("schemaIntegrityScore",
                mapped.isEmpty() && unmappedEntities.isEmpty() ? 100 :
                        Math.max(0, 100 - unmappedEntities.size() * 15 - unmappedTables.size() * 5));
        return result;
    }

    @Tool(name = "get_migration_timeline",
          description = "Returns all database migrations in version order with the entities/columns " +
              "each migration affects. Useful for understanding schema evolution history. " +
              "Example: trace which migration added the 'deleted_at' soft-delete column.")
    public List<Map<String, Object>> getMigrationTimeline() {
        var graph = activeEngine.get().getGraph();
        List<Map<String, Object>> timeline = new ArrayList<>();

        for (SpecterNode migNode : graph.findNodesByType(NodeType.SCHEMA_MIGRATION)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("migrationId", migNode.id());
            entry.put("migrationName", migNode.name());
            entry.put("tool", migNode.metadata().get("tool"));
            entry.put("version", migNode.metadata().get("version"));

            List<String> tables = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            for (SpecterEdge edge : graph.getOutgoingEdges(migNode.id())) {
                if (edge.type() != EdgeType.CONTAINS) continue;
                graph.getNode(edge.targetId()).ifPresent(target -> {
                    if (target.type() == NodeType.SCHEMA_TABLE) {
                        tables.add(target.name());
                    }
                    if (target.type() == NodeType.SCHEMA_COLUMN) {
                        columns.add(target.metadata().get("table") + "." + target.name());
                    }
                });
            }
            entry.put("tablesAdded", tables);
            entry.put("columnsAdded", columns);
            entry.put("totalChanges", tables.size() + columns.size());
            timeline.add(entry);
        }

        return timeline;
    }

    @Tool(name = "get_websocket_endpoint",
          description = "Returns the WebSocket endpoint URL for subscribing to real-time graph updates. " +
              "Connect to /specter-ws using STOMP and subscribe to /topic/graph-updates " +
              "to receive live notifications whenever the graph is re-analyzed.")
    public Map<String, Object> getWebSocketEndpoint() {
        return Map.of(
                "endpoint", "/specter-ws",
                "protocol", "STOMP over WebSocket",
                "topics", List.of(
                        Map.of("destination", "/topic/graph-updates",
                                "description", "Full graph summary after each analysis pass"),
                        Map.of("destination", "/topic/health-updates",
                                "description", "Architectural health score update"),
                        Map.of("destination", "/topic/analysis-progress",
                                "description", "Per-resolver progress with node/edge counts")
                ),
                "sockJsFallback", "/specter-ws",
                "allowedOrigins", "*"
        );
    }

    @Tool(name = "suggest_fix",
          description = "Given a nodeId and issue description from get_architectural_health output, " +
              "uses AI to generate a specific, minimal code fix for the architectural issue. " +
              "Returns before/after code snippets and migration risk assessment. " +
              "Example: 'suggest_fix for class:com.example.OrderController ARCH-001 violation'")
    public Map<String, Object> suggestFix(
            @ToolParam(description = "Node ID from the architectural health report") String nodeId,
            @ToolParam(description = "Issue description or rule ID to fix") String issueDescription) {
        var graph = activeEngine.get().getGraph();
        var suggestion = remediationEngine.suggestFix(nodeId, issueDescription, graph);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", suggestion.nodeId());
        result.put("issue", suggestion.issue());
        result.put("suggestion", suggestion.suggestion());
        result.put("codeSnippets", suggestion.codeSnippets());
        result.put("downtimeRequired", suggestion.downtimeRequired());
        result.put("effortEstimate", suggestion.effortEstimate());
        return result;
    }

    @Tool(name = "auto_remediate_all",
          description = "Runs the full health check, then for each CRITICAL issue generates " +
              "a specific code fix using AI. Returns a prioritized remediation backlog " +
              "with effort estimates and risk levels. " +
              "Example: generate a complete refactoring plan for the entire codebase.")
    public Map<String, Object> autoRemediateAll() {
        var engine = activeEngine.get();
        var result = new LinkedHashMap<String, Object>();

        ArchitecturalHealthAnalyzer healthAnalyzer = new ArchitecturalHealthAnalyzer(engine.getGraph());
        ArchitecturalHealthAnalyzer.HealthReport health = healthAnalyzer.analyze();

        result.put("overallScore", health.overallScore());

        List<Map<String, Object>> backlog = new ArrayList<>();

        for (DimensionScore dim : health.dimensions().values()) {
            for (RiskScore risk : dim.criticalIssues()) {
                var suggestion = remediationEngine.suggestFix(
                        risk.nodeId(), risk.reason(), engine.getGraph());
                backlog.add(Map.of(
                        "dimension", dim.name(),
                        "score", dim.score(),
                        "issue", risk.reason(),
                        "severity", risk.level().name(),
                        "nodeId", risk.nodeId(),
                        "suggestion", suggestion.suggestion(),
                        "codeSnippets", suggestion.codeSnippets(),
                        "effort", suggestion.effortEstimate()
                ));
            }
        }

        backlog.sort((a, b) -> Integer.compare(
                (int) a.get("score"), (int) b.get("score")));

        result.put("remediationBacklog", backlog);
        result.put("totalIssues", backlog.size());
        return result;
    }

    @Tool(name = "audit_native_compatibility",
          description = "Analyzes the codebase for Spring Boot Native / GraalVM AOT compilation risks: " +
              "reflection usage without hints, dynamic proxy creation, resource loading patterns, " +
              "and Spring scope incompatibilities. Returns a readiness score for native compilation. " +
              "Example: assess native compilation readiness before switching to spring-boot-starter-native.")
    public Map<String, Object> auditNativeCompatibility() {
        var graph = activeEngine.get().getGraph();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> issues = new ArrayList<>();
        int reflectionIssues = 0;
        int proxyIssues = 0;
        int resourceIssues = 0;
        int scopeIssues = 0;
        int lazyIssues = 0;
        int aotFriendlyCount = 0;

        for (SpecterNode node : graph.findNodesByType(NodeType.COMPATIBILITY_ISSUE)) {
            var meta = node.metadata();
            String issue = meta.getOrDefault("issue", "");
            String className = meta.getOrDefault("className", "");
            String recommendation = meta.getOrDefault("recommendation", "");

            issues.add(Map.of(
                    "nodeId", node.id(),
                    "className", className,
                    "issue", issue,
                    "recommendation", recommendation
            ));

            String lower = issue.toLowerCase();
            if (lower.contains("reflection")) reflectionIssues++;
            if (lower.contains("proxy")) proxyIssues++;
            if (lower.contains("resource")) resourceIssues++;
            if (lower.contains("scope")) scopeIssues++;
            if (lower.contains("lazy")) lazyIssues++;
        }

        for (SpecterNode node : graph.allNodes()) {
            if ("true".equals(node.metadata().get("hasAotHints"))) {
                aotFriendlyCount++;
            }
        }

        int readinessScore = 100
                - reflectionIssues * 15
                - proxyIssues * 12
                - resourceIssues * 8
                - scopeIssues * 10
                - lazyIssues * 10
                + aotFriendlyCount * 5;
        readinessScore = Math.max(0, Math.min(100, readinessScore));

        result.put("readinessScore", readinessScore);
        result.put("readinessLabel", readinessScore >= 80 ? "Near-ready"
                : readinessScore >= 50 ? "Work needed" : "Not ready");
        result.put("totalIssues", issues.size());
        result.put("reflectionIssues", reflectionIssues);
        result.put("proxyIssues", proxyIssues);
        result.put("resourceIssues", resourceIssues);
        result.put("scopeIssues", scopeIssues);
        result.put("lazyIssues", lazyIssues);
        result.put("aotFriendlyComponents", aotFriendlyCount);
        result.put("issues", issues);
        return result;
    }

    private String gradeScore(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    private Map<String, Object> nodeToSummary(SpecterNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node.id());
        m.put("name", node.name());
        m.put("type", node.type().name());
        return m;
    }

    /**
     * Walks upward from the configured source root to find the nearest
     * directory containing a {@code pom.xml} or {@code build.gradle}.
     *
     * @return the project root, or {@link Optional#empty()} if none found
     *         (prevents falling through to the filesystem root).
     */
    private Optional<Path> resolveProjectRoot() {
        Path p = Path.of(activeSourceRoot.get()).toAbsolutePath();
        while (p != null) {
            if (Files.exists(p.resolve("pom.xml")) || Files.exists(p.resolve("build.gradle"))) {
                return Optional.of(p);
            }
            p = p.getParent();
        }
        return Optional.empty();
    }

    private int findLayer(List<String> layers, String moduleName) {
        for (int i = 0; i < layers.size(); i++) {
            if (moduleName.contains(layers.get(i).trim().toLowerCase())) return i;
        }
        return -1;
    }

    private static Map<String, Object> nodeToMap(SpecterNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node.id());
        m.put("name", node.name());
        m.put("type", node.type().name());
        m.put("metadata", node.metadata());
        return m;
    }
}
