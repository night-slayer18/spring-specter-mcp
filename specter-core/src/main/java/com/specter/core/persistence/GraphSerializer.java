package com.specter.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Serializes/deserializes {@link SpecterGraph} to/from JSON files using Jackson.
 * Enables persistent graph storage and multi-project support.
 */
public class GraphSerializer {

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void saveToFile(SpecterGraph graph, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (SpecterNode node : graph.allNodes()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.id());
            n.put("name", node.name());
            n.put("type", node.type().name());
            n.put("metadata", new LinkedHashMap<>(node.metadata()));
            nodeList.add(n);
        }
        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (SpecterEdge edge : graph.allEdges()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("sourceId", edge.sourceId());
            e.put("targetId", edge.targetId());
            e.put("type", edge.type().name());
            edgeList.add(e);
        }
        data.put("nodes", nodeList);
        data.put("edges", edgeList);
        mapper.writeValue(outputPath.toFile(), data);
    }

    public static SpecterGraph loadFromFile(Path inputPath) throws IOException {
        SpecterGraph graph = new SpecterGraph();
        if (!Files.exists(inputPath)) return graph;

        Map<String, Object> data = mapper.readValue(inputPath.toFile(),
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodeList = (List<Map<String, Object>>) data.get("nodes");
        if (nodeList != null) {
            for (var map : nodeList) {
                String id = (String) map.get("id");
                String name = (String) map.get("name");
                NodeType type = NodeType.valueOf((String) map.get("type"));
                @SuppressWarnings("unchecked")
                Map<String, String> metadata = (Map<String, String>) map.getOrDefault("metadata", Map.of());
                graph.addNode(new SpecterNode(id, name, type, new LinkedHashMap<>(metadata)));
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> edgeList = (List<Map<String, String>>) data.get("edges");
        if (edgeList != null) {
            for (var map : edgeList) {
                String sourceId = map.get("sourceId");
                String targetId = map.get("targetId");
                EdgeType type = EdgeType.valueOf(map.get("type"));
                graph.addEdge(new SpecterEdge(sourceId, targetId, type));
            }
        }
        return graph;
    }

    public static String projectHash(Path sourceRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sourceRoot.toAbsolutePath().toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(sourceRoot.hashCode());
        }
    }
}
