package com.specter.core;

import com.specter.core.graph.EdgeType;
import com.specter.core.graph.NodeType;
import com.specter.core.graph.SpecterEdge;
import com.specter.core.graph.SpecterNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SpecterAnalysisEngineTest {

    private SpecterAnalysisEngine engine;
    private Path sourceRoot;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Path.of("src/test/resources/dummyapp").toAbsolutePath();
        engine = new SpecterAnalysisEngine(null, Set.of());
        engine.analyze(sourceRoot);
    }

    @Test
    void testComponentScanning() {
        var graph = engine.getGraph();

        assertThat(graph.allNodes())
                .extracting(SpecterNode::type)
                .contains(NodeType.CONTROLLER, NodeType.SERVICE, NodeType.REPOSITORY);

        SpecterNode controller = graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.CONTROLLER)
                .findFirst().orElseThrow();
        assertThat(controller.name()).contains("OrderController");

        SpecterNode service = graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.SERVICE)
                .findFirst().orElseThrow();
        assertThat(service.name()).contains("OrderServiceImpl");

        SpecterNode repository = graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.REPOSITORY)
                .findFirst().orElseThrow();
        assertThat(repository.name()).contains("OrderRepository");

        assertThat(engine.getRegistry().size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void testAopProxyResolution() {
        var graph = engine.getGraph();

        List<SpecterNode> proxyNodes = graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.PROXY)
                .collect(Collectors.toList());

        assertThat(proxyNodes).isNotEmpty();
        assertThat(proxyNodes).anyMatch(n ->
                n.metadata().containsKey("PROXY_STEREOTYPE") &&
                n.metadata().get("PROXY_STEREOTYPE").contains("TRANSACTION_INTERCEPTOR"));

        String serviceNodeId = graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.SERVICE)
                .map(SpecterNode::id)
                .findFirst().orElseThrow();
        SpecterNode proxy = proxyNodes.get(0);

        Set<String> allCallsTargets = graph.allEdges().stream()
                .filter(e -> e.type() == EdgeType.CALLS)
                .map(SpecterEdge::targetId)
                .collect(Collectors.toSet());
        assertThat(allCallsTargets).containsAnyOf(proxy.id(), serviceNodeId);
    }
}
