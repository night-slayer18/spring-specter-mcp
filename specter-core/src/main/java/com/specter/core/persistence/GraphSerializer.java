package com.specter.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Serializes/deserializes {@link SpecterGraph} to/from JSON files using Jackson.
 * Enables persistent graph storage, multi-project support, and snapshot diffing.
 *
 * <h3>Snapshot format</h3>
 * Snapshots written via {@link #saveSnapshot} carry {@code label} and
 * {@code capturedAt} metadata in the JSON root, which {@link #loadSnapshotMetadata}
 * can recover without loading the full graph. Plain {@link #saveToFile} writes
 * graph-only data (no metadata).
 */
public class GraphSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Graph persistence ────────────────────────────────────────────────

    public static void saveToFile(SpecterGraph graph, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        MAPPER.writeValue(outputPath.toFile(), buildGraphMap(graph));
    }

    public static SpecterGraph loadFromFile(Path inputPath) throws IOException {
        if (!Files.exists(inputPath)) return new SpecterGraph();
        Map<String, Object> data = MAPPER.readValue(inputPath.toFile(),
                new TypeReference<Map<String, Object>>() {});
        return hydrateGraph(data);
    }

    // ── Snapshot persistence (includes label + capturedAt) ───────────────

    /**
     * Saves a graph snapshot with label and capture timestamp embedded in the JSON.
     * Use {@link #loadSnapshotMetadata} to recover the timestamp without loading
     * the full graph.
     */
    public static void saveSnapshot(SpecterGraph graph, String label, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("capturedAt", Instant.now().toString());
        data.putAll(buildGraphMap(graph));
        MAPPER.writeValue(outputPath.toFile(), data);
    }

    /**
     * Reads {@code label} and {@code capturedAt} from a snapshot file without
     * loading the full node/edge data. Falls back to file-system mtime if
     * {@code capturedAt} is absent (e.g. snapshots written by an older version).
     */
    public static SnapshotMetadata loadSnapshotMetadata(Path inputPath) throws IOException {
        if (!Files.exists(inputPath)) return null;
        Map<String, Object> data = MAPPER.readValue(inputPath.toFile(),
                new TypeReference<Map<String, Object>>() {});
        String fileName = inputPath.getFileName().toString().replace(".json", "");
        String label = (String) data.getOrDefault("label", fileName);
        String capturedAtStr = (String) data.get("capturedAt");
        Instant capturedAt = capturedAtStr != null
                ? Instant.parse(capturedAtStr)
                : Instant.ofEpochMilli(Files.getLastModifiedTime(inputPath).toMillis());
        return new SnapshotMetadata(label, capturedAt);
    }

    /** Metadata record returned by {@link #loadSnapshotMetadata}. */
    public record SnapshotMetadata(String label, Instant capturedAt) {}

    // ── Project ID helper ────────────────────────────────────────────────

    /**
     * Returns a stable 16-character hex prefix of the SHA-256 hash of the
     * normalized source-root path. Used as a deterministic project identifier.
     */
    public static String projectHash(Path sourceRoot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sourceRoot.toAbsolutePath().toString().getBytes());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(sourceRoot.hashCode());
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private static Map<String, Object> buildGraphMap(SpecterGraph graph) {
        List<Map<String, Object>> nodeList = new ArrayList<>(graph.nodeCount());
        for (SpecterNode node : graph.allNodes()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id",       node.id());
            n.put("name",     node.name());
            n.put("type",     node.type().name());
            n.put("metadata", new LinkedHashMap<>(node.metadata()));
            nodeList.add(n);
        }

        List<Map<String, Object>> edgeList = new ArrayList<>(graph.edgeCount());
        for (SpecterEdge edge : graph.allEdges()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("sourceId", edge.sourceId());
            e.put("targetId", edge.targetId());
            e.put("type",     edge.type().name());
            edgeList.add(e);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodeList);
        data.put("edges", edgeList);
        return data;
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<>() {};

    private static SpecterGraph hydrateGraph(Map<String, Object> data) {
        SpecterGraph graph = new SpecterGraph();

        // Use TypeReference-driven re-deserialization to prevent ClassCastException
        // when Jackson deserializes numeric JSON values as Integer rather than String.
        Object rawNodes = data.get("nodes");
        if (rawNodes != null) {
            List<Map<String, Object>> nodeList = MAPPER.convertValue(rawNodes, LIST_MAP_TYPE);
            for (var map : nodeList) {
                String id   = String.valueOf(map.get("id"));
                String name = String.valueOf(map.get("name"));
                NodeType type = NodeType.valueOf(String.valueOf(map.get("type")));
                Object rawMeta = map.getOrDefault("metadata", Map.of());
                @SuppressWarnings("unchecked")
                Map<String, Object> rawMetaMap = (rawMeta instanceof Map<?,?> m)
                        ? (Map<String, Object>) m : Map.of();
                Map<String, String> metadata = new LinkedHashMap<>();
                rawMetaMap.forEach((k, v) -> metadata.put(k, String.valueOf(v)));
                graph.addNode(new SpecterNode(id, name, type, metadata));
            }
        }

        Object rawEdges = data.get("edges");
        if (rawEdges != null) {
            List<Map<String, Object>> edgeList = MAPPER.convertValue(rawEdges, LIST_MAP_TYPE);
            for (var map : edgeList) {
                graph.addEdge(new SpecterEdge(
                        String.valueOf(map.get("sourceId")),
                        String.valueOf(map.get("targetId")),
                        EdgeType.valueOf(String.valueOf(map.get("type")))));
            }
        }
        return graph;
    }
}

