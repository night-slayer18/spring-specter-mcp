package com.specter.core;

import com.specter.core.graph.*;
import com.specter.core.index.SpecterIndexSearcher;
import com.specter.core.index.SpecterIndexWriter;
import com.specter.core.parser.*;
import com.specter.core.registry.BeanRegistry;
import com.specter.core.registry.BeanRegistry.BeanMetadata;
import com.specter.core.watcher.SourceChangeTracker;
import com.specter.core.watcher.SourceChangeTracker.ChangeSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * Runtime Context Simulator orchestration engine.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li><b>Pass 1 — Component Scan:</b> {@link BeanRegistryResolver} simulates
 *       {@code @ComponentScan} to build the bean registry.</li>
 *   <li><b>Pass 2 — Resolution:</b> Dependency injection, AOP proxy rewiring,
 *       Spring Data, Messaging, and bytecode proxy analysis execute against
 *       the active bean set only.</li>
 *   <li><b>Index:</b> All nodes and edges are indexed into Lucene for fuzzy search.</li>
 * </ol>
 */
@Slf4j
public class SpecterAnalysisEngine {

    private final SpecterGraph graph;
    private final SpecterIndexWriter indexWriter;
    private final SpecterIndexSearcher indexSearcher;
    private final BeanRegistry registry;

    private final List<FrameworkResolver> pass1Resolvers;
    private final List<FrameworkResolver> pass2Resolvers;

    public SpecterAnalysisEngine() throws IOException {
        this(null, Set.of());
    }

    public SpecterAnalysisEngine(Path classesRoot) throws IOException {
        this(classesRoot, Set.of());
    }

    /**
     * @param classesRoot    optional path to compiled {@code .class} files for
     *                       bytecode proxy analysis; may be {@code null}
     * @param activeProfiles set of active Spring profiles for {@code @Profile} filtering
     */
    public SpecterAnalysisEngine(Path classesRoot, Set<String> activeProfiles) throws IOException {
        this.graph = new SpecterGraph();
        this.indexWriter = new SpecterIndexWriter();
        this.indexSearcher = new SpecterIndexSearcher(indexWriter.getDirectory());
        this.registry = new BeanRegistry();

        this.pass1Resolvers = List.of();
        this.pass2Resolvers = buildPass2Resolvers(classesRoot);
    }

    private List<FrameworkResolver> buildPass2Resolvers(Path classesRoot) {
        List<FrameworkResolver> resolvers = new ArrayList<>();
        resolvers.add(new SpringDependencyResolver(graph, registry));
        resolvers.add(new AopProxyResolver(graph, registry));
        resolvers.add(new WebMvcResolver(graph));
        resolvers.add(new SpringDataResolver(graph));
        resolvers.add(new MessagingResolver(graph));
        resolvers.add(new SecurityFilterChainResolver(graph));
        resolvers.add(new ConfigurationPropertiesResolver(graph));
        resolvers.add(new OpenApiResolver(graph));
        resolvers.add(new ServiceCallResolver(graph));
        resolvers.add(new TestCoverageResolver(graph));
        if (classesRoot != null) {
            resolvers.add(new ProxyAnalysisResolver(graph, classesRoot));
        }
        return List.copyOf(resolvers);
    }

    /**
     * Runs the full two-pass pipeline against the given source root.
     *
     * @param sourceRoot    root of the project source tree
     * @param activeProfiles set of active Spring profiles for {@code @Profile} evaluation
     */
    public void analyze(Path sourceRoot, Set<String> activeProfiles) throws IOException {
        log.info("Starting Runtime Context Simulation of: {}", sourceRoot.toAbsolutePath());
        log.info("Active profiles: {}", activeProfiles);

        // ═══ Pass 1: Component Scan Simulation ═══════════════════════════
        BeanRegistryResolver scanResolver = new BeanRegistryResolver(
                registry, sourceRoot, null, activeProfiles);
        scanResolver.resolve(sourceRoot);
        log.info("Pass 1 complete — {} active beans registered", registry.size());

        // ═══ Pass 2: Dependency & Proxy Resolution ═══════════════════════
        for (FrameworkResolver resolver : pass2Resolvers) {
            resolver.resolve(sourceRoot);
            log.info("{} complete — {} nodes, {} edges",
                    resolver.name(), graph.nodeCount(), graph.edgeCount());
        }

        // ═══ Index ═══════════════════════════════════════════════════════
        indexWriter.indexNodes(graph.allNodes());
        graph.allEdges().forEach(indexWriter::indexEdge);
        indexWriter.commit();

        log.info("Analysis complete. Graph: {} nodes, {} edges. Registry: {} beans.",
                graph.nodeCount(), graph.edgeCount(), registry.size());
    }

    /**
     * Runs the pipeline without profile filtering (all beans active).
     */
    public void analyze(Path sourceRoot) throws IOException {
        analyze(sourceRoot, Set.of());
    }

    /**
     * Factory method for multi-module Maven/Gradle projects.
     * Discovers all submodules, runs a unified Pass 1 across all source roots,
     * and produces a single graph with cross-module dependency edges.
     */
    public static SpecterAnalysisEngine forMultiModuleProject(Path projectRoot,
                                                               Set<String> activeProfiles) throws IOException {
        SpecterAnalysisEngine engine = new SpecterAnalysisEngine(null, activeProfiles);
        ModuleTopologyResolver moduleResolver = new ModuleTopologyResolver(engine.graph);
        var moduleMap = moduleResolver.discover(projectRoot);

        // Unified Pass 1 across all module source roots
        for (var entry : moduleMap.entrySet()) {
            ModuleTopologyResolver.ModuleDescriptor md = entry.getValue();
            BeanRegistryResolver scanResolver = new BeanRegistryResolver(
                    engine.registry, md.sourceRoot(), null, activeProfiles);
            scanResolver.resolve(md.sourceRoot());
        }
        log.info("Multi-module Pass 1 complete — {} active beans registered", engine.registry.size());

        // Pass 2 across all source roots
        for (var entry : moduleMap.entrySet()) {
            ModuleTopologyResolver.ModuleDescriptor md = entry.getValue();
            for (FrameworkResolver resolver : engine.pass2Resolvers) {
                resolver.resolve(md.sourceRoot());
                log.info("[{}] {} complete — {} nodes, {} edges",
                        md.artifactId(), resolver.name(),
                        engine.graph.nodeCount(), engine.graph.edgeCount());
            }
        }

        engine.reindex();
        log.info("Multi-module analysis complete. Graph: {} nodes, {} edges. Registry: {} beans.",
                engine.graph.nodeCount(), engine.graph.edgeCount(), engine.registry.size());

        return engine;
    }

    /**
     * Incremental analysis — only re-processes files changed since the
     * last analysis run. Significantly faster at enterprise scale.
     */
    public ChangeSet analyzeIncremental(Path sourceRoot, Set<String> activeProfiles) throws IOException {
        SourceChangeTracker tracker = new SourceChangeTracker(sourceRoot);
        ChangeSet changes = tracker.computeChanges(sourceRoot);

        if (!changes.hasChanges()) {
            log.info("No changes detected — skipping re-analysis");
            return changes;
        }

        log.info("Incremental analysis: +{} added, ~{} modified, -{} deleted",
                changes.added().size(), changes.modified().size(), changes.deleted().size());

        for (Path deleted : changes.deleted()) {
            graph.removeNodesForFile(deleted);
        }

        Set<Path> reprocess = new HashSet<>();
        reprocess.addAll(changes.added());
        reprocess.addAll(changes.modified());
        for (Path file : reprocess) {
            graph.removeNodesForFile(file);
        }

        for (FrameworkResolver resolver : pass2Resolvers) {
            try {
                resolver.resolveFiles(reprocess);
            } catch (UnsupportedOperationException e) {
                resolver.resolve(sourceRoot);
            }
        }

        reindex();
        tracker.persistFingerprints(sourceRoot);

        log.info("Incremental analysis complete. Graph: {} nodes, {} edges. Registry: {} beans.",
                graph.nodeCount(), graph.edgeCount(), registry.size());

        return changes;
    }

    private void reindex() throws IOException {
        indexWriter.indexNodes(graph.allNodes());
        graph.allEdges().forEach(indexWriter::indexEdge);
        indexWriter.commit();
    }

    // ── Query API ────────────────────────────────────────────────────────

    public List<SpecterIndexSearcher.SearchHit> search(String query, int maxResults) {
        return indexSearcher.search(query, maxResults);
    }

    public BlastRadiusResult calculateBlastRadius(String className, int maxDepth) {
        String nodeId = findNodeIdByClassName(className);
        if (nodeId == null) {
            return new BlastRadiusResult(className, Collections.emptySet(), Collections.emptyList());
        }

        Set<String> resolvedIds = resolveProxies(Set.of(nodeId));
        Set<String> allAffected = new HashSet<>();
        for (String resolvedId : resolvedIds) {
            allAffected.addAll(graph.blastRadius(resolvedId, maxDepth));
        }

        List<SpecterNode> affectedNodes = allAffected.stream()
                .map(graph::getNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        return new BlastRadiusResult(className, allAffected, affectedNodes);
    }

    public MessageFlowResult traceMessageFlow(String channelName) {
        String topicNodeId = "message_topic:" + channelName;
        List<SpecterNode> producers = new ArrayList<>();
        List<SpecterNode> consumers = new ArrayList<>();

        for (SpecterEdge edge : graph.getIncomingEdges(topicNodeId)) {
            if (edge.type() == EdgeType.PUBLISHES_TO) {
                graph.getNode(edge.sourceId()).ifPresent(producers::add);
            }
        }
        for (SpecterEdge edge : graph.getOutgoingEdges(topicNodeId)) {
            if (edge.type() == EdgeType.SUBSCRIBES_FROM) {
                graph.getNode(edge.targetId()).ifPresent(consumers::add);
            }
        }
        for (SpecterEdge edge : graph.getOutgoingEdges(topicNodeId)) {
            if (edge.type() == EdgeType.PUBLISHES_TO) {
                graph.getNode(edge.targetId()).ifPresent(consumers::add);
            }
        }

        return new MessageFlowResult(channelName, producers, consumers);
    }

    /**
     * Simulates what Spring would inject for a given type.
     * Uses the BeanRegistry to resolve {@code @Primary} and {@code @Qualifier}.
     */
    public InjectionSimulation simulateDependencyInjection(String interfaceName,
                                                            String qualifier) {
        Optional<String> resolved = registry.resolveInjectionTarget(interfaceName, qualifier);

        List<Map<String, String>> candidates = new ArrayList<>();
        for (String activeClass : registry.activeClasses()) {
            BeanMetadata meta = registry.getMetadata(activeClass).orElse(null);
            if (meta != null && meta.interfaces().contains(interfaceName)) {
                Map<String, String> candidate = new LinkedHashMap<>();
                candidate.put("className", activeClass);
                candidate.put("beanName", meta.beanName());
                candidate.put("primary", String.valueOf(meta.primary()));
                if (meta.qualifier() != null) {
                    candidate.put("qualifier", meta.qualifier());
                }
                candidates.add(candidate);
            }
        }

        return new InjectionSimulation(interfaceName, qualifier,
                resolved.orElse(null), candidates);
    }

    /**
     * Traverses a service execution path and returns all transaction
     * boundaries (PROXY nodes with TRANSACTION_INTERCEPTOR stereotype)
     * found along the CALLS chain.
     */
    public TransactionBoundaryResult getTransactionBoundaries(String className, int maxDepth) {
        String nodeId = findNodeIdByClassName(className);
        if (nodeId == null) {
            return new TransactionBoundaryResult(className, List.of());
        }

        List<SpecterNode> boundaries = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> depthQueue = new ArrayDeque<>();
        queue.add(nodeId);
        depthQueue.add(0);
        visited.add(nodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depthQueue.poll();
            if (depth > maxDepth) continue;

            for (SpecterEdge edge : graph.getOutgoingEdges(current)) {
                if (!(edge instanceof SpecterEdge(var s, var target, var et) && et == EdgeType.CALLS)) continue;

                graph.getNode(target).ifPresent(node -> {
                    if (node instanceof SpecterNode(var id, var name, var nt, var metadata) && nt == NodeType.PROXY) {
                        String stereotype = metadata.getOrDefault("PROXY_STEREOTYPE", "");
                        if (stereotype.contains("TRANSACTION_INTERCEPTOR")) {
                            boundaries.add(node);
                        }
                    }
                });

                if (visited.add(target)) {
                    queue.add(target);
                    depthQueue.add(depth + 1);
                }
            }
        }

        return new TransactionBoundaryResult(className, boundaries);
    }

    public DependencyCycleResult analyzeDependencyCycle() {
        List<List<SpecterNode>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        Map<String, String> parent = new HashMap<>();

        for (SpecterNode node : graph.allNodes()) {
            if (!visited.contains(node.id())) {
                dfsCycleDetection(node.id(), visited, inStack, parent, cycles);
            }
        }

        return new DependencyCycleResult(cycles);
    }

    private void dfsCycleDetection(String nodeId, Set<String> visited,
                                    Set<String> inStack, Map<String, String> parent,
                                    List<List<SpecterNode>> cycles) {
        visited.add(nodeId);
        inStack.add(nodeId);

        for (SpecterEdge edge : graph.getOutgoingEdges(nodeId)) {
            if (edge.type() != EdgeType.INJECTS) continue;
            String target = edge.targetId();

            parent.putIfAbsent(target, nodeId);

            if (!visited.contains(target)) {
                dfsCycleDetection(target, visited, inStack, parent, cycles);
            } else if (inStack.contains(target)) {
                List<SpecterNode> cycle = new ArrayList<>();
                String current = nodeId;
                while (!current.equals(target)) {
                    graph.getNode(current).ifPresent(cycle::add);
                    current = parent.getOrDefault(current, target);
                }
                graph.getNode(target).ifPresent(cycle::add);
                if (!cycle.isEmpty()) cycles.add(cycle);
            }
        }

        inStack.remove(nodeId);
    }

    private Set<String> resolveProxies(Set<String> nodeIds) {
        Set<String> resolved = new HashSet<>(nodeIds);
        Deque<String> queue = new ArrayDeque<>(nodeIds);
        Set<String> visited = new HashSet<>(nodeIds);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (SpecterEdge edge : graph.getOutgoingEdges(current)) {
                if ((edge.type() == EdgeType.IMPLEMENTS || edge.type() == EdgeType.EXTENDS
                        || edge.type() == EdgeType.CALLS)
                        && visited.add(edge.targetId())) {
                    resolved.add(edge.targetId());
                    queue.add(edge.targetId());
                }
            }
        }
        return resolved;
    }

    public Map<String, Object> getGraphSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNodes", graph.nodeCount());
        summary.put("totalEdges", graph.edgeCount());
        summary.put("activeBeans", registry.size());

        Map<String, Long> nodesByType = graph.allNodes().stream()
                .collect(Collectors.groupingBy(n -> n.type().name(), Collectors.counting()));
        summary.put("nodesByType", nodesByType);

        Map<String, Long> edgesByType = graph.allEdges().stream()
                .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting()));
        summary.put("edgesByType", edgesByType);

        return summary;
    }

    public List<ApiEndpoint> getApiSurface() {
        return graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.CONTROLLER_ENDPOINT)
                .map(node -> {
                    var meta = node.metadata();
                    String controllerClass = meta.getOrDefault("controllerClass", "unknown");
                    String httpVerb = meta.getOrDefault("httpVerb", "REQUEST");
                    String path = meta.getOrDefault("path", "/");
                    return new ApiEndpoint(httpVerb, path, controllerClass,
                            meta.getOrDefault("methodName", ""),
                            meta.getOrDefault("produces", null),
                            meta.getOrDefault("consumes", null));
                })
                .sorted(Comparator.comparing(ApiEndpoint::path)
                        .thenComparing(ApiEndpoint::httpVerb))
                .collect(Collectors.toList());
    }

    public SpecterGraph getGraph() { return graph; }
    public BeanRegistry getRegistry() { return registry; }

    private String findNodeIdByClassName(String className) {
        String exactId = "class:" + className;
        if (graph.getNode(exactId).isPresent()) return exactId;
        return graph.findNodeByName(className).map(SpecterNode::id).orElse(null);
    }

    public void close() {
        indexWriter.close();
    }

    // ── Result records ───────────────────────────────────────────────────

    public record BlastRadiusResult(
            String className,
            Set<String> affectedNodeIds,
            List<SpecterNode> affectedNodes
    ) {}

    public record MessageFlowResult(
            String channelName,
            List<SpecterNode> producers,
            List<SpecterNode> consumers
    ) {}

    public record DependencyCycleResult(
            List<List<SpecterNode>> cycles
    ) {
        public boolean hasCycles() {
            return cycles != null && !cycles.isEmpty();
        }
    }

    public record InjectionSimulation(
            String interfaceName,
            String qualifier,
            String resolvedClass,
            List<Map<String, String>> candidates
    ) {}

    public record TransactionBoundaryResult(
            String className,
            List<SpecterNode> boundaries
    ) {}

    public record ApiEndpoint(
            String httpVerb,
            String path,
            String controllerClass,
            String methodName,
            String produces,
            String consumes
    ) {}
}
