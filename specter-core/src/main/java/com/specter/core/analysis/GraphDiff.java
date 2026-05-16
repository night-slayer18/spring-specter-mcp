package com.specter.core.analysis;

import com.specter.core.graph.*;

import java.time.Instant;
import java.util.*;

/**
 * Computes structural differences between two graph snapshots,
 * including change impact analysis (blast radius of combined changes).
 */
public final class GraphDiff {

    private GraphDiff() {}

    // ── Snapshot record ─────────────────────────────────────────────────

    public record GraphSnapshot(
            Map<String, SpecterNode> nodes,
            Set<SpecterEdge> edges,
            Instant capturedAt,
            String label
    ) {}

    // ── Diff result records ─────────────────────────────────────────────

    public record DiffResult(
            List<SpecterNode> addedNodes,
            List<SpecterNode> removedNodes,
            List<SpecterNode> changedNodes,
            List<SpecterEdge> addedEdges,
            List<SpecterEdge> removedEdges,
            List<ImpactedComponent> impactedByChanges
    ) {
        public boolean hasChanges() {
            return !addedNodes.isEmpty() || !removedNodes.isEmpty()
                    || !changedNodes.isEmpty() || !addedEdges.isEmpty()
                    || !removedEdges.isEmpty();
        }
    }

    public record ImpactedComponent(
            String nodeId,
            String name,
            NodeType type,
            String reason
    ) {}

    // ── Diff computation ────────────────────────────────────────────────

    public static DiffResult diff(GraphSnapshot before, GraphSnapshot after, SpecterGraph liveGraph) {
        Map<String, SpecterNode> beforeNodes = before.nodes();
        Map<String, SpecterNode> afterNodes = after.nodes();
        Set<SpecterEdge> beforeEdges = before.edges();
        Set<SpecterEdge> afterEdges = after.edges();

        // Node diff
        List<SpecterNode> addedNodes = new ArrayList<>();
        List<SpecterNode> removedNodes = new ArrayList<>();
        List<SpecterNode> changedNodes = new ArrayList<>();

        Set<String> beforeIds = new HashSet<>(beforeNodes.keySet());
        Set<String> afterIds = new HashSet<>(afterNodes.keySet());

        for (String id : afterIds) {
            if (!beforeIds.contains(id)) {
                addedNodes.add(afterNodes.get(id));
            } else {
                SpecterNode beforeNode = beforeNodes.get(id);
                SpecterNode afterNode = afterNodes.get(id);
                if (nodeChanged(beforeNode, afterNode)) {
                    changedNodes.add(afterNode);
                }
            }
        }

        for (String id : beforeIds) {
            if (!afterIds.contains(id)) {
                removedNodes.add(beforeNodes.get(id));
            }
        }

        // Edge diff
        List<SpecterEdge> addedEdges = new ArrayList<>();
        List<SpecterEdge> removedEdges = new ArrayList<>();

        for (SpecterEdge e : afterEdges) {
            if (!beforeEdges.contains(e)) {
                addedEdges.add(e);
            }
        }

        for (SpecterEdge e : beforeEdges) {
            if (!afterEdges.contains(e)) {
                removedEdges.add(e);
            }
        }

        // Impact analysis — what else is affected?
        List<ImpactedComponent> impacted = computeImpact(
                addedNodes, removedNodes, changedNodes, addedEdges, removedEdges, liveGraph);

        return new DiffResult(addedNodes, removedNodes, changedNodes,
                addedEdges, removedEdges, impacted);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static boolean nodeChanged(SpecterNode before, SpecterNode after) {
        if (!Objects.equals(before.name(), after.name())) return true;
        if (before.type() != after.type()) return true;
        if (!Objects.equals(before.metadata(), after.metadata())) return true;
        return false;
    }

    private static List<ImpactedComponent> computeImpact(
            List<SpecterNode> added, List<SpecterNode> removed,
            List<SpecterNode> changed,
            List<SpecterEdge> addedEdges, List<SpecterEdge> removedEdges,
            SpecterGraph liveGraph) {

        Set<String> directlyChangedIds = new HashSet<>();
        for (SpecterNode n : added) directlyChangedIds.add(n.id());
        for (SpecterNode n : removed) directlyChangedIds.add(n.id());
        for (SpecterNode n : changed) directlyChangedIds.add(n.id());

        Set<String> impacted = new HashSet<>();
        for (String changedId : directlyChangedIds) {
            // Any node connected to the changed node via INJECTS or CALLS
            for (SpecterEdge edge : liveGraph.allEdges()) {
                if (edge.sourceId().equals(changedId) && !directlyChangedIds.contains(edge.targetId())) {
                    impacted.add(edge.targetId());
                }
                if (edge.targetId().equals(changedId) && !directlyChangedIds.contains(edge.sourceId())) {
                    impacted.add(edge.sourceId());
                }
            }
        }

        return impacted.stream()
                .map(id -> liveGraph.getNode(id).map(n -> new ImpactedComponent(
                        n.id(), n.name(), n.type(),
                        "dependency of changed node"))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    // ── Factory for snapshots ───────────────────────────────────────────

    public static GraphSnapshot fromGraph(SpecterGraph graph, String label) {
        Map<String, SpecterNode> nodes = new LinkedHashMap<>();
        for (SpecterNode n : graph.allNodes()) {
            nodes.put(n.id(), n);
        }
        return new GraphSnapshot(nodes, Set.copyOf(graph.allEdges()),
                Instant.now(), label);
    }
}
