package com.specter.core.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record SpecterNode(
    String id,
    String name,
    NodeType type,
    Map<String, String> metadata
) {
    public SpecterNode {
        metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    public static SpecterNode of(String id, String name, NodeType type) {
        return new SpecterNode(id, name, type, Map.of());
    }

    public SpecterNode withMetadata(String key, String value) {
        Map<String, String> merged = new HashMap<>(this.metadata);
        merged.put(key, value);
        return new SpecterNode(this.id, this.name, this.type, merged);
    }
}
