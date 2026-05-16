package com.specter.server.api;

import com.specter.core.graph.*;

import java.util.*;

/**
 * Exports {@link SpecterGraph} to multiple formats:
 * JSON, GraphML, Cypher (Neo4j), and DOT (Graphviz).
 */
public class GraphExporter {

    public static Map<String, Object> export(SpecterGraph graph, String format) {
        return switch (format.toLowerCase()) {
            case "json" -> toJson(graph);
            case "cypher" -> toCypher(graph);
            case "graphml" -> toGraphML(graph);
            case "dot" -> toDot(graph);
            default -> Map.of("error", true, "message", "Unsupported format: " + format
                    + ". Supported: json, cypher, graphml, dot");
        };
    }

    private static Map<String, Object> toJson(SpecterGraph graph) {
        List<Map<String, Object>> nodes = graph.allNodes().stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.id());
                    m.put("name", n.name());
                    m.put("type", n.type().name());
                    m.put("metadata", n.metadata());
                    return m;
                })
                .toList();
        List<Map<String, Object>> edges = graph.allEdges().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sourceId", e.sourceId());
                    m.put("targetId", e.targetId());
                    m.put("type", e.type().name());
                    return m;
                })
                .toList();
        return Map.of("format", "json", "nodes", (Object) nodes, "edges", (Object) edges);
    }

    private static Map<String, Object> toCypher(SpecterGraph graph) {
        StringBuilder sb = new StringBuilder();
        for (SpecterNode node : graph.allNodes()) {
            String label = node.type().name();
            String safeName = node.name().replace("'", "\\'");
            sb.append("CREATE (n:").append(label)
                    .append(" {id: '").append(node.id())
                    .append("', name: '").append(safeName).append("'});\n");
        }
        for (SpecterEdge edge : graph.allEdges()) {
            sb.append("MATCH (a {id: '").append(edge.sourceId()).append("'}), ")
                    .append("(b {id: '").append(edge.targetId()).append("'})\n")
                    .append("CREATE (a)-[:").append(edge.type().name()).append("]->(b);\n");
        }
        return Map.of("format", "cypher", "cypher", sb.toString());
    }

    private static Map<String, Object> toGraphML(SpecterGraph graph) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        sb.append("<graph id=\"specter\" edgedefault=\"directed\">\n");
        for (SpecterNode node : graph.allNodes()) {
            sb.append("  <node id=\"").append(escapeXml(node.id()))
                    .append("\" name=\"").append(escapeXml(node.name())).append("\"/>\n");
        }
        for (SpecterEdge edge : graph.allEdges()) {
            sb.append("  <edge source=\"").append(escapeXml(edge.sourceId()))
                    .append("\" target=\"").append(escapeXml(edge.targetId()))
                    .append("\" label=\"").append(edge.type().name()).append("\"/>\n");
        }
        sb.append("</graph>\n</graphml>");
        return Map.of("format", "graphml", "graphml", sb.toString());
    }

    private static Map<String, Object> toDot(SpecterGraph graph) {
        StringBuilder sb = new StringBuilder("digraph Specter {\n");
        sb.append("  rankdir=LR;\n");
        for (SpecterNode node : graph.allNodes()) {
            String nodeId = node.id().replaceAll("[^a-zA-Z0-9_]", "_");
            sb.append("  ").append(nodeId).append(" [label=\"")
                    .append(node.name()).append("\"];\n");
        }
        for (SpecterEdge edge : graph.allEdges()) {
            String src = edge.sourceId().replaceAll("[^a-zA-Z0-9_]", "_");
            String tgt = edge.targetId().replaceAll("[^a-zA-Z0-9_]", "_");
            sb.append("  ").append(src).append(" -> ").append(tgt)
                    .append(" [label=\"").append(edge.type().name()).append("\"];\n");
        }
        sb.append("}");
        return Map.of("format", "dot", "dot", sb.toString());
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
