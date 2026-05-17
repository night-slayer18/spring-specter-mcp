package com.specter.core.graph;

import com.specter.core.persistence.GraphSerializer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core graph data structure for the Specter Runtime Context Simulator.
 *
 * <p>Edge lookup is O(1) amortized via dual adjacency-list indexes
 * ({@code outgoing} and {@code incoming}) maintained on every mutation.
 * All collections are backed by {@link ConcurrentHashMap} for safe
 * concurrent access during parallel Pass 2 resolver execution.
 */
public class SpecterGraph {

    private final Map<String, SpecterNode> nodes = new ConcurrentHashMap<>(256);

    // Dual adjacency indexes — O(1) outgoing/incoming edge lookup
    private final Map<String, Set<SpecterEdge>> outgoing = new ConcurrentHashMap<>(256);
    private final Map<String, Set<SpecterEdge>> incoming = new ConcurrentHashMap<>(256);

    // Full edge set for serialization and iteration
    private final Set<SpecterEdge> edges = ConcurrentHashMap.newKeySet(1024);

    // ── Mutation ─────────────────────────────────────────────────────────

    public void addNode(SpecterNode node) {
        nodes.put(node.id(), node);
    }

    public void addEdge(SpecterEdge edge) {
        if (edges.add(edge)) {
            outgoing.computeIfAbsent(edge.sourceId(), k -> ConcurrentHashMap.newKeySet()).add(edge);
            incoming.computeIfAbsent(edge.targetId(), k -> ConcurrentHashMap.newKeySet()).add(edge);
        }
    }

    public void addEdge(String sourceId, String targetId, EdgeType type) {
        addEdge(new SpecterEdge(sourceId, targetId, type));
    }

    // ── Queries ──────────────────────────────────────────────────────────

    public Optional<SpecterNode> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** O(1) — backed by adjacency index. */
    public List<SpecterEdge> getOutgoingEdges(String nodeId) {
        Set<SpecterEdge> set = outgoing.get(nodeId);
        return set == null ? List.of() : List.copyOf(set);
    }

    /** O(1) — backed by adjacency index. */
    public List<SpecterEdge> getIncomingEdges(String nodeId) {
        Set<SpecterEdge> set = incoming.get(nodeId);
        return set == null ? List.of() : List.copyOf(set);
    }

    public List<SpecterNode> findNodesByType(NodeType type) {
        return nodes.values().stream()
                .filter(n -> n.type() == type)
                .collect(Collectors.toList());
    }

    /**
     * Returns all nodes whose {@code name} equals the given value.
     * Prefer this over {@link #findNodeByName} when duplicate simple
     * names across packages are possible.
     */
    public List<SpecterNode> findNodesByName(String name) {
        return nodes.values().stream()
                .filter(n -> n.name().equals(name))
                .toList();
    }

    /**
     * Returns the first node matching the given name.
     * Delegates to {@link #findNodesByName}; use that method when
     * multiple matches are expected.
     */
    public Optional<SpecterNode> findNodeByName(String name) {
        List<SpecterNode> matches = findNodesByName(name);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    // ── Graph traversal ──────────────────────────────────────────────────

    public Set<String> blastRadius(String startNodeId, int maxDepth) {
        Set<String> visited = HashSet.newHashSet(64);
        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> depthQueue = new ArrayDeque<>();
        queue.add(startNodeId);
        depthQueue.add(0);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depthQueue.poll();
            if (depth >= maxDepth) continue;

            for (SpecterEdge edge : getOutgoingEdges(current)) {
                if (visited.add(edge.targetId())) {
                    queue.add(edge.targetId());
                    depthQueue.add(depth + 1);
                }
            }
            for (SpecterEdge edge : getIncomingEdges(current)) {
                if (visited.add(edge.sourceId())) {
                    queue.add(edge.sourceId());
                    depthQueue.add(depth + 1);
                }
            }
        }
        visited.remove(startNodeId);
        return visited;
    }

    // ── Metrics ──────────────────────────────────────────────────────────

    public int nodeCount() { return nodes.size(); }
    public int edgeCount()  { return edges.size(); }

    public Collection<SpecterNode> allNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Set<SpecterEdge> allEdges() {
        return Collections.unmodifiableSet(edges);
    }

    // ── Mutation — clear / remove ─────────────────────────────────────────

    public void clear() {
        nodes.clear();
        edges.clear();
        outgoing.clear();
        incoming.clear();
    }

    /**
     * Removes all nodes sourced from the given file and cleans up
     * every edge that referenced those nodes from both adjacency indexes.
     */
    public void removeNodesForFile(Path file) {
        String filePath = file.toAbsolutePath().toString();
        List<String> toRemove = nodes.values().stream()
                .filter(n -> filePath.equals(n.metadata().get("sourceFile")))
                .map(SpecterNode::id)
                .toList();

        for (String id : toRemove) {
            nodes.remove(id);

            // Clean outgoing adjacency: remove edges from this node
            Set<SpecterEdge> outEdges = outgoing.remove(id);
            if (outEdges != null) {
                for (SpecterEdge e : outEdges) {
                    edges.remove(e);
                    Set<SpecterEdge> inSet = incoming.get(e.targetId());
                    if (inSet != null) inSet.remove(e);
                }
            }

            // Clean incoming adjacency: remove edges to this node
            Set<SpecterEdge> inEdges = incoming.remove(id);
            if (inEdges != null) {
                for (SpecterEdge e : inEdges) {
                    edges.remove(e);
                    Set<SpecterEdge> outSet = outgoing.get(e.sourceId());
                    if (outSet != null) outSet.remove(e);
                }
            }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────

    public void saveToFile(Path outputPath) throws IOException {
        GraphSerializer.saveToFile(this, outputPath);
    }

    public static SpecterGraph loadFromFile(Path inputPath) throws IOException {
        return GraphSerializer.loadFromFile(inputPath);
    }
}
