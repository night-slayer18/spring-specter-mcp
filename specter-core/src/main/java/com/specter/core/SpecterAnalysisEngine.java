package com.specter.core;

import com.specter.core.analysis.GraphDiff;
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
import java.util.concurrent.*;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * Runtime Context Simulator orchestration engine.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li><b>Pass 1 — Component Scan:</b> {@link BeanRegistryResolver} simulates
 *       {@code @ComponentScan} to build the bean registry.</li>
 *   <li><b>Pass 2 — Resolution:</b> All framework resolvers run in <em>parallel</em>
 *       using a virtual-thread executor ({@link Executors#newVirtualThreadPerTaskExecutor()}).
 *       Each resolver failure is isolated — a single resolver error does not abort the pipeline.
 *       </li>
 *   <li><b>Index:</b> All nodes and edges are indexed into Lucene for fuzzy search.</li>
 * </ol>
 */
@Slf4j
public class SpecterAnalysisEngine {

    private final SpecterGraph graph;
    private final SpecterIndexWriter indexWriter;
    private final SpecterIndexSearcher indexSearcher;
    private final BeanRegistry registry;
    /** Volatile ensures visibility across virtual threads without synchronization overhead. */
    private volatile AnalysisProgressListener progressListener;

    /**
     * Sequential resolvers — must run in order (dependency graph matters):
     * SpringDependencyResolver adds CALLS/INJECTS edges that AopProxyResolver
     * consumes. Running them in parallel would produce an empty proxy list.
     */
    private final List<FrameworkResolver> sequentialResolvers;

    /**
     * Parallel resolvers — independent of each other and of the sequential
     * resolvers. They only read source files and add distinct node/edge types.
     * Run concurrently on virtual threads after sequential resolvers complete.
     */
    private final List<FrameworkResolver> parallelResolvers;

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
        this.sequentialResolvers = buildSequentialResolvers(classesRoot);
        this.parallelResolvers   = buildParallelResolvers();
    }

    private List<FrameworkResolver> buildSequentialResolvers(Path classesRoot) {
        // Order matters: SpringDependencyResolver must add CALLS/INJECTS edges
        // before AopProxyResolver reads them for proxy rewiring.
        List<FrameworkResolver> resolvers = new ArrayList<>();
        resolvers.add(new SpringDependencyResolver(graph, registry));
        resolvers.add(new AopProxyResolver(graph, registry));
        if (classesRoot != null) {
            resolvers.add(new ProxyAnalysisResolver(graph, classesRoot));
        }
        return List.copyOf(resolvers);
    }

    private List<FrameworkResolver> buildParallelResolvers() {
        // These resolvers are independent of each other and of SpringDependencyResolver —
        // they only read source files and add their own node/edge types to the graph.
        return List.of(
                new WebMvcResolver(graph),
                new SpringDataResolver(graph),
                new MessagingResolver(graph),
                new SecurityFilterChainResolver(graph),
                new ConfigurationPropertiesResolver(graph),
                new OpenApiResolver(graph),
                new ServiceCallResolver(graph),
                new TestCoverageResolver(graph),
                new ObservabilityResolver(graph),
                new PerformancePatternResolver(graph),
                new DatabaseSchemaResolver(graph),
                new GraalVmCompatibilityResolver(graph)
        );
    }

    /**
     * Runs the full two-pass pipeline against the given source root.
     *
     * <p>Pass 2 resolvers execute in parallel on virtual threads. Each resolver
     * failure is caught and logged — the pipeline continues with the remaining resolvers.
     *
     * @param sourceRoot    root of the project source tree
     * @param activeProfiles set of active Spring profiles for {@code @Profile} evaluation
     */
    public void analyze(Path sourceRoot, Set<String> activeProfiles) throws IOException {
        log.info("Starting Runtime Context Simulation of: {}", sourceRoot.toAbsolutePath());
        log.info("Active profiles: {}", activeProfiles);

        // ═══ Pass 1: Component Scan Simulation (sequential — registry must be fully built first)
        BeanRegistryResolver scanResolver = new BeanRegistryResolver(registry, activeProfiles);
        scanResolver.resolve(sourceRoot);
        log.info("Pass 1 complete — {} active beans registered", registry.size());

        // ═══ Pass 2: Sequential resolvers first (order-dependent)
        for (FrameworkResolver resolver : sequentialResolvers) {
            try {
                resolver.resolve(sourceRoot);
                log.info("{} complete — {} nodes, {} edges",
                        resolver.name(), graph.nodeCount(), graph.edgeCount());
                notifyProgress(resolver.name(), graph.nodeCount(), graph.edgeCount());
            } catch (Exception e) {
                log.error("Sequential resolver '{}' failed — continuing", resolver.name(), e);
            }
        }

        // ═══ Pass 2: Parallel resolvers (independent, no ordering constraints)
        runResolversInParallel(parallelResolvers, sourceRoot);

        // ═══ Index ═══════════════════════════════════════════════════════
        reindex();
        notifyComplete();

        log.info("Analysis complete. Graph: {} nodes, {} edges. Registry: {} beans.",
                graph.nodeCount(), graph.edgeCount(), registry.size());
    }

    /**
     * Executes all resolvers in parallel using a virtual-thread-per-task executor.
     * Each resolver failure is isolated — exceptions are caught, logged, and do not
     * abort the pipeline. All resolvers are guaranteed to complete before this method returns.
     */
    private void runResolversInParallel(List<FrameworkResolver> resolvers, Path sourceRoot) {
        List<Callable<Void>> tasks = resolvers.stream()
                .map(resolver -> (Callable<Void>) () -> {
                    try {
                        resolver.resolve(sourceRoot);
                        log.info("{} complete — {} nodes, {} edges",
                                resolver.name(), graph.nodeCount(), graph.edgeCount());
                        notifyProgress(resolver.name(), graph.nodeCount(), graph.edgeCount());
                    } catch (Exception e) {
                        log.error("Resolver '{}' failed — continuing with remaining resolvers",
                                resolver.name(), e);
                    }
                    return null;
                })
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Pass 2 interrupted — analysis may be incomplete", e);
        }
    }

    private void notifyProgress(String phase, int nodeCount, int edgeCount) {
        AnalysisProgressListener l = progressListener;
        if (l != null) {
            try { l.onProgress(phase, nodeCount, edgeCount); }
            catch (Exception e) { log.warn("Progress listener failed", e); }
        }
    }

    private void notifyComplete() {
        AnalysisProgressListener l = progressListener;
        if (l != null) {
            try { l.onComplete(graph); }
            catch (Exception e) { log.warn("Complete listener failed", e); }
        }
    }

    /** Runs the pipeline without profile filtering (all beans active). */
    public void analyze(Path sourceRoot) throws IOException {
        analyze(sourceRoot, Set.of());
    }

    /**
     * Factory method for multi-module Maven/Gradle projects.
     */
    public static SpecterAnalysisEngine forMultiModuleProject(Path projectRoot,
                                                               Set<String> activeProfiles) throws IOException {
        SpecterAnalysisEngine engine = new SpecterAnalysisEngine(null, activeProfiles);
        ModuleTopologyResolver moduleResolver = new ModuleTopologyResolver(engine.graph);
        var moduleMap = moduleResolver.discover(projectRoot);

        // Unified Pass 1 across all module source roots (must be sequential)
        for (var entry : moduleMap.entrySet()) {
            ModuleTopologyResolver.ModuleDescriptor md = entry.getValue();
            BeanRegistryResolver scanResolver = new BeanRegistryResolver(engine.registry, activeProfiles);
            scanResolver.resolve(md.sourceRoot());
        }
        log.info("Multi-module Pass 1 complete — {} active beans registered", engine.registry.size());

        // Pass 2 across all source roots — sequential then parallel per module
        for (var entry : moduleMap.entrySet()) {
            ModuleTopologyResolver.ModuleDescriptor md = entry.getValue();
            for (FrameworkResolver resolver : engine.sequentialResolvers) {
                try { resolver.resolve(md.sourceRoot()); }
                catch (Exception e) { log.error("Sequential resolver '{}' failed", resolver.name(), e); }
            }
            engine.runResolversInParallel(engine.parallelResolvers, md.sourceRoot());
            log.info("[{}] Pass 2 complete — {} nodes, {} edges",
                    md.artifactId(), engine.graph.nodeCount(), engine.graph.edgeCount());
        }

        engine.reindex();
        log.info("Multi-module analysis complete. Graph: {} nodes, {} edges. Registry: {} beans.",
                engine.graph.nodeCount(), engine.graph.edgeCount(), engine.registry.size());
        return engine;
    }

    /**
     * Incremental analysis — only re-processes files changed since the last run.
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

        // Sequential resolvers first (AopProxyResolver depends on SpringDependencyResolver)
        for (FrameworkResolver resolver : sequentialResolvers) {
            try {
                resolver.resolveFiles(reprocess);
            } catch (UnsupportedOperationException e) {
                try { resolver.resolve(sourceRoot); }
                catch (Exception ex) { log.error("Resolver '{}' failed during incremental", resolver.name(), ex); }
            } catch (Exception e) {
                log.error("Resolver '{}' failed during incremental", resolver.name(), e);
            }
        }

        // Parallel resolvers
        List<Callable<Void>> tasks = parallelResolvers.stream()
                .map(resolver -> (Callable<Void>) () -> {
                    try {
                        resolver.resolveFiles(reprocess);
                    } catch (UnsupportedOperationException e) {
                        try { resolver.resolve(sourceRoot); }
                        catch (Exception ex) {
                            log.error("Resolver '{}' failed during incremental analysis", resolver.name(), ex);
                        }
                    } catch (Exception e) {
                        log.error("Resolver '{}' failed during incremental analysis", resolver.name(), e);
                    }
                    return null;
                })
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Incremental Pass 2 interrupted", e);
        }

        reindex();
        tracker.persistFingerprints(sourceRoot);

        log.info("Incremental analysis complete. Graph: {} nodes, {} edges. Registry: {} beans.",
                graph.nodeCount(), graph.edgeCount(), registry.size());

        return changes;
    }

    private void reindex() throws IOException {
        indexWriter.clearIndex();
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
            if (edge.type() == EdgeType.PUBLISHES_TO)
                graph.getNode(edge.sourceId()).ifPresent(producers::add);
        }
        for (SpecterEdge edge : graph.getOutgoingEdges(topicNodeId)) {
            if (edge.type() == EdgeType.SUBSCRIBES_FROM)
                graph.getNode(edge.targetId()).ifPresent(consumers::add);
            if (edge.type() == EdgeType.PUBLISHES_TO)
                graph.getNode(edge.targetId()).ifPresent(consumers::add);
        }
        return new MessageFlowResult(channelName, producers, consumers);
    }

    public InjectionSimulation simulateDependencyInjection(String interfaceName, String qualifier) {
        Optional<String> resolved = registry.resolveInjectionTarget(interfaceName, qualifier);
        List<Map<String, String>> candidates = new ArrayList<>();
        for (String activeClass : registry.activeClasses()) {
            BeanMetadata meta = registry.getMetadata(activeClass).orElse(null);
            if (meta != null && meta.interfaces().contains(interfaceName)) {
                Map<String, String> candidate = new LinkedHashMap<>();
                candidate.put("className", activeClass);
                candidate.put("beanName", meta.beanName());
                candidate.put("primary", String.valueOf(meta.primary()));
                if (meta.qualifier() != null) candidate.put("qualifier", meta.qualifier());
                candidates.add(candidate);
            }
        }
        return new InjectionSimulation(interfaceName, qualifier, resolved.orElse(null), candidates);
    }

    public TransactionBoundaryResult getTransactionBoundaries(String className, int maxDepth) {
        String nodeId = findNodeIdByClassName(className);
        if (nodeId == null) return new TransactionBoundaryResult(className, List.of());

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
                if (!(edge instanceof SpecterEdge(var _, var target, var et) && et == EdgeType.CALLS)) continue;
                graph.getNode(target).ifPresent(node -> {
                    if (node instanceof SpecterNode(var _, var _, var nt, var metadata) && nt == NodeType.PROXY) {
                        if (metadata.getOrDefault("PROXY_STEREOTYPE", "").contains("TRANSACTION_INTERCEPTOR")) {
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
                        || edge.type() == EdgeType.CALLS) && visited.add(edge.targetId())) {
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
        summary.put("nodesByType", graph.allNodes().stream()
                .collect(Collectors.groupingBy(n -> n.type().name(), Collectors.counting())));
        summary.put("edgesByType", graph.allEdges().stream()
                .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting())));
        return summary;
    }

    public List<ApiEndpoint> getApiSurface() {
        return graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.CONTROLLER_ENDPOINT)
                .map(node -> {
                    var meta = node.metadata();
                    return new ApiEndpoint(
                            meta.getOrDefault("httpVerb", "REQUEST"),
                            meta.getOrDefault("path", "/"),
                            meta.getOrDefault("controllerClass", "unknown"),
                            meta.getOrDefault("methodName", ""),
                            meta.getOrDefault("produces", null),
                            meta.getOrDefault("consumes", null));
                })
                .sorted(Comparator.comparing(ApiEndpoint::path).thenComparing(ApiEndpoint::httpVerb))
                .collect(Collectors.toList());
    }

    public void setProgressListener(AnalysisProgressListener listener) {
        this.progressListener = listener;
    }

    public SpecterGraph getGraph()    { return graph; }
    public BeanRegistry getRegistry() { return registry; }

    public GraphDiff.GraphSnapshot snapshot(String label) {
        return GraphDiff.fromGraph(graph, label);
    }

    private String findNodeIdByClassName(String className) {
        String exactId = "class:" + className;
        if (graph.getNode(exactId).isPresent()) return exactId;
        // findNodesByName returns all matches; warn if ambiguous
        List<SpecterNode> matches = graph.findNodesByName(className);
        if (matches.size() > 1) {
            log.warn("Ambiguous class name '{}' — {} nodes matched; using first match: {}",
                    className, matches.size(), matches.getFirst().id());
        }
        return matches.isEmpty() ? null : matches.getFirst().id();
    }

    public void close() {
        indexWriter.close();
    }

    // ── Result records ───────────────────────────────────────────────────

    public record BlastRadiusResult(String className, Set<String> affectedNodeIds, List<SpecterNode> affectedNodes) {}
    public record MessageFlowResult(String channelName, List<SpecterNode> producers, List<SpecterNode> consumers) {}
    public record DependencyCycleResult(List<List<SpecterNode>> cycles) {
        public boolean hasCycles() { return cycles != null && !cycles.isEmpty(); }
    }
    public record InjectionSimulation(String interfaceName, String qualifier,
                                       String resolvedClass, List<Map<String, String>> candidates) {}
    public record TransactionBoundaryResult(String className, List<SpecterNode> boundaries) {}
    public record ApiEndpoint(String httpVerb, String path, String controllerClass,
                               String methodName, String produces, String consumes) {}
}
