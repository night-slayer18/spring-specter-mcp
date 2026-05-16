package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.*;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects outbound HTTP calls to external services via
 * {@code @FeignClient}, {@code WebClient}, and {@code RestTemplate}.
 * Creates microservice call topology in the graph for distributed tracing.
 */
@Slf4j
public class ServiceCallResolver implements FrameworkResolver {

    private final SpecterGraph graph;

    public ServiceCallResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Microservice Call Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        try (var files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(this::resolveFile);
        }
        log.info("Microservice call topology analysis complete");
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());
                parseFeignClient(cls, className);
                parseWebClientCalls(cls, className, cu.toString());
            });
        } catch (IOException e) {
            log.debug("Failed to parse for service calls: {}", file, e);
        }
    }

    private void parseFeignClient(ClassOrInterfaceDeclaration cls, String className) {
        for (var ann : cls.getAnnotations()) {
            if (!ann.getNameAsString().equals("FeignClient")) continue;

            String serviceName = "unknown-service";
            if (ann instanceof NormalAnnotationExpr na) {
                MemberValuePair pair = na.getPairs().stream()
                        .filter(p -> "name".equals(p.getNameAsString())
                                || "value".equals(p.getNameAsString()))
                        .findFirst().orElse(null);
                if (pair != null && pair.getValue().isStringLiteralExpr()) {
                    serviceName = pair.getValue().asStringLiteralExpr().getValue();
                } else {
                    serviceName = className.toLowerCase();
                }
            }

            String serviceId = "external_service:" + serviceName;
            SpecterNode serviceNode = SpecterNode.of(serviceId, serviceName, NodeType.EXTERNAL_SERVICE)
                    .withMetadata("protocol", "Feign");
            graph.addNode(serviceNode);

            String feignId = "feign_client:" + className;
            SpecterNode feignNode = SpecterNode.of(feignId, className, NodeType.FEIGN_CLIENT)
                    .withMetadata("serviceName", serviceName);
            graph.addNode(feignNode);
            graph.addEdge(feignId, serviceId, EdgeType.CALLS_REMOTE);

            graph.getNode("class:" + className).ifPresent(classNode ->
                    graph.addEdge(classNode.id(), feignId, EdgeType.CALLS_REMOTE));
        }
    }

    private void parseWebClientCalls(ClassOrInterfaceDeclaration cls, String className, String source) {
        // Detect WebClient.get().uri("http://...") or RestTemplate calls
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("WebClient") || trimmed.contains("RestTemplate")) {
                int uriIdx = trimmed.indexOf(".uri(");
                if (uriIdx > 0) {
                    String uriPart = trimmed.substring(uriIdx + 5);
                    int endIdx = uriPart.indexOf(')');
                    if (endIdx > 0) {
                        String uri = uriPart.substring(0, endIdx).replace("\"", "").trim();
                        String serviceName = extractServiceName(uri);
                        createExternalService(className, serviceName, "WebClient/RestTemplate");
                    }
                }
            }
        }
    }

    private String extractServiceName(String uri) {
        if (uri.contains("://")) {
            String hostPart = uri.substring(uri.indexOf("://") + 3);
            int slashIdx = hostPart.indexOf('/');
            if (slashIdx > 0) return hostPart.substring(0, slashIdx);
            return hostPart;
        }
        return uri;
    }

    private void createExternalService(String className, String serviceName, String protocol) {
        String serviceId = "external_service:" + serviceName;
        if (graph.getNode(serviceId).isEmpty()) {
            SpecterNode serviceNode = SpecterNode.of(serviceId, serviceName, NodeType.EXTERNAL_SERVICE)
                    .withMetadata("protocol", protocol);
            graph.addNode(serviceNode);
        }
        graph.getNode("class:" + className).ifPresent(classNode ->
                graph.addEdge(classNode.id(), serviceId, EdgeType.CALLS_REMOTE));
    }
}
