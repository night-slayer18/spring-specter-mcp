package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps HTTP API entry points by parsing {@code @RestController} and
 * {@code @Controller} methods annotated with {@code @GetMapping},
 * {@code @PostMapping}, {@code @PutMapping}, {@code @DeleteMapping},
 * {@code @PatchMapping}, and {@code @RequestMapping}.
 *
 * <p>Creates {@link NodeType#CONTROLLER_ENDPOINT} nodes storing the HTTP
 * verb and path in metadata, and draws {@link EdgeType#ROUTES_TO} edges
 * from each endpoint to its controller class node.
 */
@Slf4j
public class WebMvcResolver implements FrameworkResolver {

    private static final Set<String> HTTP_MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping"
    );

    private static final Map<String, String> VERB_MAP = Map.ofEntries(
            Map.entry("RequestMapping", "REQUEST"),
            Map.entry("GetMapping", "GET"),
            Map.entry("PostMapping", "POST"),
            Map.entry("PutMapping", "PUT"),
            Map.entry("DeleteMapping", "DELETE"),
            Map.entry("PatchMapping", "PATCH")
    );

    private final SpecterGraph graph;

    public WebMvcResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Web MVC Endpoints";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(this::resolveFile);
        }
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            List<ClassOrInterfaceDeclaration> types = cu.findAll(ClassOrInterfaceDeclaration.class);

            for (ClassOrInterfaceDeclaration cls : types) {
                if (!isController(cls)) continue;
                processController(cls, file, cu);
            }
        } catch (IOException e) {
            log.warn("Failed to parse file for WebMvc resolution: {}", file, e);
        }
    }

    private boolean isController(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> switch (a.getNameAsString()) {
                    case "RestController", "Controller" -> true;
                    default -> false;
                });
    }

    private void processController(ClassOrInterfaceDeclaration cls, Path file,
                                    CompilationUnit cu) {
        String controllerName = cls.getFullyQualifiedName()
                .orElse(cls.getNameAsString());
        String controllerNodeId = "class:" + controllerName;

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                String annName = ann.getNameAsString();
                if (!HTTP_MAPPING_ANNOTATIONS.contains(annName)) continue;

                var mappingProps = extractMappingProperties(ann, cls);
                String httpVerb = VERB_MAP.getOrDefault(annName, "REQUEST");
                String path = mappingProps.path();
                String fullPath = buildFullPath(path, cls);

                String endpointNodeId = "endpoint:" + httpVerb + ":" + fullPath
                        + ":" + method.getNameAsString();
                SpecterNode endpointNode = SpecterNode.of(endpointNodeId,
                                httpVerb + " " + fullPath,
                                NodeType.CONTROLLER_ENDPOINT)
                        .withMetadata("httpVerb", httpVerb)
                        .withMetadata("path", fullPath)
                        .withMetadata("methodName", method.getNameAsString())
                        .withMetadata("controllerClass", controllerName)
                        .withMetadata("sourceFile", file.toString());
                if (mappingProps.produces() != null) {
                    endpointNode = endpointNode.withMetadata("produces", mappingProps.produces());
                }
                if (mappingProps.consumes() != null) {
                    endpointNode = endpointNode.withMetadata("consumes", mappingProps.consumes());
                }
                graph.addNode(endpointNode);

                graph.addEdge(endpointNodeId, controllerNodeId, EdgeType.ROUTES_TO);
            }
        }
    }

    private MappingProps extractMappingProperties(AnnotationExpr ann,
                                                   ClassOrInterfaceDeclaration cls) {
        String path = "";
        String produces = null;
        String consumes = null;

        if (ann.isNormalAnnotationExpr()) {
            NormalAnnotationExpr normal = ann.asNormalAnnotationExpr();
            for (MemberValuePair pair : normal.getPairs()) {
                String key = pair.getNameAsString();
                String val = pair.getValue().asStringLiteralExpr()
                        .asString();
                switch (key) {
                    case "value" -> { if (path.isEmpty()) path = val; }
                    case "path" -> path = val;
                    case "produces" -> produces = val;
                    case "consumes" -> consumes = val;
                }
            }
        } else if (ann.isSingleMemberAnnotationExpr()) {
            SingleMemberAnnotationExpr single = ann.asSingleMemberAnnotationExpr();
            path = single.getMemberValue().asStringLiteralExpr().asString();
        }

        if (path.isEmpty()) path = "/";

        return new MappingProps(path, produces, consumes);
    }

    private String buildFullPath(String methodPath, ClassOrInterfaceDeclaration cls) {
        String basePath = extractClassPath(cls);
        if (basePath.isEmpty()) return methodPath;

        final String cp = basePath;
        return switch (methodPath) {
            case "/" -> cp;
            case String p when cp.endsWith("/") -> cp + (p.startsWith("/") ? p.substring(1) : p);
            case String p when p.startsWith("/") -> cp + p;
            default -> cp + "/" + methodPath;
        };
    }

    private String extractClassPath(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if ("RequestMapping".equals(ann.getNameAsString())) {
                if (ann.isNormalAnnotationExpr()) {
                    for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                        String key = pair.getNameAsString();
                        if ("value".equals(key) || "path".equals(key)) {
                            return pair.getValue().asStringLiteralExpr().asString();
                        }
                    }
                } else if (ann.isSingleMemberAnnotationExpr()) {
                    return ann.asSingleMemberAnnotationExpr()
                            .getMemberValue().asStringLiteralExpr().asString();
                }
            }
        }
        return "";
    }

    private record MappingProps(String path, String produces, String consumes) {}
}
