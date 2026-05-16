package com.specter.server.tools;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.SpecterAnalysisEngine.*;
import com.specter.core.graph.SpecterNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SpecterMcpTools {

    private final SpecterAnalysisEngine engine;

    public SpecterMcpTools(SpecterAnalysisEngine engine) {
        this.engine = engine;
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
