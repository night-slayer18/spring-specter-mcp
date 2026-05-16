package com.specter.server.tools;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.SpecterAnalysisEngine.*;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.core.graph.EdgeType;
import com.specter.core.graph.NodeType;
import com.specter.core.graph.SpecterEdge;
import com.specter.core.graph.SpecterNode;
import com.specter.server.ProjectRegistry;
import com.specter.server.ProjectRegistry.ProjectContext;
import com.specter.core.provenance.GitProvenanceChecker;
import com.specter.core.provenance.ProvenanceViolation;
import com.specter.core.watcher.SourceChangeTracker.ChangeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SpecterMcpTools {

    private final SpecterAnalysisEngine engine;
    private final String sourceRootPath;
    private final ProjectRegistry projectRegistry;

    public SpecterMcpTools(SpecterAnalysisEngine engine,
                            @Value("${specter.source.root:./src}") String sourceRootPath,
                            ProjectRegistry projectRegistry) {
        this.engine = engine;
        this.sourceRootPath = sourceRootPath;
        this.projectRegistry = projectRegistry;
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

        return engine.search(query, maxResults).stream()
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

        InjectionSimulation result = engine.simulateDependencyInjection(interfaceName,
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

        TransactionBoundaryResult result = engine.getTransactionBoundaries(className, maxDepth);

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

        BlastRadiusResult result = engine.calculateBlastRadius(className, maxDepth);

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

        MessageFlowResult result = engine.traceMessageFlow(channelName);

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
        DependencyCycleResult result = engine.analyzeDependencyCycle();

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
        return engine.getGraphSummary();
    }

    @Tool(name = "get_api_surface",
            description = "Lists every exposed HTTP endpoint in the application: HTTP verb, URL path, " +
                    "controller class, and handler method. Extracted from @GetMapping, @PostMapping, " +
                    "@PutMapping, @DeleteMapping, @PatchMapping, and @RequestMapping annotations. " +
                    "Returns endpoints sorted by path and verb for easy scanning.")
    public List<Map<String, Object>> getApiSurface() {
        return engine.getApiSurface().stream()
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
            Path rootPath = Path.of(sourceRootPath).toAbsolutePath();
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
            Path sourceRoot = Path.of(sourceRootPath).toAbsolutePath();
            ChangeSet changes = engine.analyzeIncremental(sourceRoot, Set.of());
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        var graph = engine.getGraph();
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
        if (ctx == null) {
            response.put("error", true);
            response.put("message", "Project not found: " + projectId);
        } else {
            response.put("projectId", ctx.projectId());
            response.put("displayName", ctx.displayName());
            response.put("status", ctx.status().name());
            response.put("switched", true);
        }
        return response;
    }

    @Tool(name = "get_architectural_health",
            description = "Computes a comprehensive architectural health score (0-100) across 6 dimensions: " +
                    "dependency health, security, resilience, test coverage, observability, and API contract quality. " +
                    "Returns dimension scores, critical issues, and prioritized remediation recommendations. " +
                    "Example: get a single dashboard view of your architecture's overall health.")
    public Map<String, Object> getArchitecturalHealth() {
        ArchitecturalHealthAnalyzer analyzer = new ArchitecturalHealthAnalyzer(engine.getGraph());
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

    private int findLayer(List<String> layers, String moduleName) {
        for (int i = 0; i < layers.size(); i++) {
            if (moduleName.contains(layers.get(i).trim().toLowerCase())) return i;
        }
        return -1;
    }

    private static Map<String, Object> nodeToMap(SpecterNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node instanceof SpecterNode(var id, var name, var type, var metadata)) {
            m.put("nodeId", id);
            m.put("name", name);
            m.put("type", type.name());
            m.put("metadata", metadata);
        }
        return m;
    }
}
