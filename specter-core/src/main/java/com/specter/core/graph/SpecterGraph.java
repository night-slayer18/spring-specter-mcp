package com.specter.core.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SpecterGraph {

    private final Map<String, SpecterNode> nodes = new ConcurrentHashMap<>();
    private final Set<SpecterEdge> edges = ConcurrentHashMap.newKeySet();

    public void addNode(SpecterNode node) {
        nodes.put(node.id(), node);
    }

    public Optional<SpecterNode> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public void addEdge(SpecterEdge edge) {
        edges.add(edge);
    }

    public void addEdge(String sourceId, String targetId, EdgeType type) {
        edges.add(new SpecterEdge(sourceId, targetId, type));
    }

    public List<SpecterEdge> getOutgoingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.sourceId().equals(nodeId))
                .collect(Collectors.toList());
    }

    public List<SpecterEdge> getIncomingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.targetId().equals(nodeId))
                .collect(Collectors.toList());
    }

    public List<SpecterNode> findNodesByType(NodeType type) {
        return nodes.values().stream()
                .filter(n -> n.type() == type)
                .collect(Collectors.toList());
    }

    public Optional<SpecterNode> findNodeByName(String name) {
        return nodes.values().stream()
                .filter(n -> n.name().equals(name))
                .findFirst();
    }

    public Set<String> blastRadius(String startNodeId, int maxDepth) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> depthQueue = new ArrayDeque<>();
        queue.add(startNodeId);
        depthQueue.add(0);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depthQueue.poll();

            if (depth >= maxDepth) continue;

            // Outgoing edges (downstream dependencies)
            for (SpecterEdge edge : getOutgoingEdges(current)) {
                String target = edge.targetId();
                if (visited.add(target)) {
                    queue.add(target);
                    depthQueue.add(depth + 1);
                }
            }

            // Incoming edges (upstream dependents — what depends on this)
            for (SpecterEdge edge : getIncomingEdges(current)) {
                String source = edge.sourceId();
                if (visited.add(source)) {
                    queue.add(source);
                    depthQueue.add(depth + 1);
                }
            }
        }
        visited.remove(startNodeId);
        return visited;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Collection<SpecterNode> allNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Set<SpecterEdge> allEdges() {
        return Collections.unmodifiableSet(edges);
    }

    public void clear() {
        nodes.clear();
        edges.clear();
    }

    public void removeNodesForFile(Path file) {
        String filePath = file.toAbsolutePath().toString();
        List<String> toRemove = nodes.values().stream()
                .filter(n -> {
                    String src = n.metadata().get("sourceFile");
                    return src != null && src.equals(filePath);
                })
                .map(SpecterNode::id)
                .toList();

        for (String id : toRemove) {
            nodes.remove(id);
            edges.removeIf(e -> e.sourceId().equals(id) || e.targetId().equals(id));
        }
    }

    public void saveToFile(Path outputPath) throws IOException {
        // Delegated to GraphSerializer
    }

    public static SpecterGraph loadFromFile(Path inputPath) throws IOException {
        // Delegated to GraphSerializer
        return new SpecterGraph();
    }
}
