package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.specter.core.graph.*;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Parses OpenAPI 3.x specs and Swagger annotations to enrich the endpoint graph
 * with contract metadata. Supports both annotation-based (@Operation, @ApiResponse)
 * and spec-file-based (openapi.yaml/json) analysis.
 */
@Slf4j
public class OpenApiResolver implements FrameworkResolver {

    private final SpecterGraph graph;

    public OpenApiResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "OpenAPI Contract Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        // Mode A: annotations
        try (var files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(this::resolveAnnotations);
        }

        // Mode B: spec files
        resolveSpecFiles(sourceRoot);

        log.info("OpenAPI contract analysis complete");
    }

    private void resolveAnnotations(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls ->
                cls.getMethods().forEach(method -> {
                    boolean hasOperation = false;
                    String[] summaryHolder = {""};
                    String[] operationIdHolder = {""};

                    for (var ann : method.getAnnotations()) {
                        String annName = ann.getNameAsString();
                        if ("Operation".equals(annName) || "ApiOperation".equals(annName)) {
                            hasOperation = true;
                            if (ann instanceof NormalAnnotationExpr na) {
                                summaryHolder[0] = extractPairValue(na, "summary");
                                operationIdHolder[0] = extractPairValue(na, "operationId");
                            }
                        }
                        if ("Schema".equals(annName)) {
                            String dtoId = "dto:" + cls.getFullyQualifiedName().orElse(cls.getNameAsString());
                            SpecterNode dtoNode = SpecterNode.of(dtoId,
                                    cls.getNameAsString(), NodeType.DATA_TRANSFER_OBJECT);
                            graph.addNode(dtoNode);
                        }
                    }

                    if (hasOperation) {
                        // Enrich existing CONTROLLER_ENDPOINT nodes
                        graph.allNodes().stream()
                                .filter(n -> n.type() == NodeType.CONTROLLER_ENDPOINT
                                        && method.getNameAsString().equals(n.metadata().get("methodName")))
                                .forEach(ep -> {
                                    if (!summaryHolder[0].isEmpty()) ep.metadata().put("operationSummary", summaryHolder[0]);
                                    if (!operationIdHolder[0].isEmpty()) ep.metadata().put("operationId", operationIdHolder[0]);
                                    ep.metadata().put("hasOpenApiAnnotation", "true");
                                });
                    }

                    // Check @ApiResponse
                    for (var ann : method.getAnnotations()) {
                        if ("ApiResponse".equals(ann.getNameAsString()) && ann instanceof SingleMemberAnnotationExpr sma) {
                            StringLiteralExpr sle = sma.getMemberValue().asStringLiteralExpr();
                            String code = sle.getValue();
                            graph.allNodes().stream()
                                    .filter(n -> n.type() == NodeType.CONTROLLER_ENDPOINT
                                            && method.getNameAsString().equals(n.metadata().get("methodName")))
                                    .forEach(ep -> ep.metadata().put("responseCode", code));
                        }
                    }
                })
            );
        } catch (IOException e) {
            log.debug("Failed to parse OpenAPI annotations: {}", file, e);
        }
    }

    private void resolveSpecFiles(Path sourceRoot) throws IOException {
        Path projectRoot = sourceRoot;
        while (projectRoot != null && !Files.exists(projectRoot.resolve("pom.xml"))) {
            projectRoot = projectRoot.getParent();
            if (projectRoot == null) break;
        }
        if (projectRoot == null) projectRoot = sourceRoot;

        for (String specName : List.of("openapi.yaml", "openapi.json", "swagger.yaml")) {
            Path specPath = projectRoot.resolve(specName);
            if (!Files.exists(specPath)) {
                specPath = projectRoot.resolve("src/main/resources/" + specName);
            }
            if (Files.exists(specPath)) {
                parseSpecFile(specPath);
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseSpecFile(Path specPath) {
        try {
            String content = Files.readString(specPath);
            Yaml yaml = new Yaml();
            Map<String, Object> spec = yaml.load(content);

            Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
            if (paths != null) {
                for (var pathEntry : paths.entrySet()) {
                    String specPathStr = pathEntry.getKey();
                    Map<String, Object> methods = (Map<String, Object>) pathEntry.getValue();
                    if (methods != null) {
                        for (var methodEntry : methods.entrySet()) {
                            String verb = methodEntry.getKey().toUpperCase();
                            // Mark matching endpoints as documented
                            graph.allNodes().stream()
                                    .filter(n -> n.type() == NodeType.CONTROLLER_ENDPOINT
                                            && verb.equalsIgnoreCase(n.metadata().get("httpVerb"))
                                            && specPathStr.equals(n.metadata().get("path")))
                                    .forEach(ep -> ep.metadata().put("hasOpenApiSpec", "true"));
                        }
                    }
                }
            }
            log.info("Parsed OpenAPI spec: {} paths", paths != null ? paths.size() : 0);
        } catch (IOException e) {
            log.warn("Failed to parse OpenAPI spec: {}", specPath, e);
        }
    }

    private String extractPairValue(NormalAnnotationExpr na, String name) {
        MemberValuePair pair = na.getPairs().stream()
                .filter(p -> name.equals(p.getNameAsString()))
                .findFirst().orElse(null);
        if (pair != null && pair.getValue().isStringLiteralExpr()) {
            return pair.getValue().asStringLiteralExpr().getValue();
        }
        return "";
    }
}
