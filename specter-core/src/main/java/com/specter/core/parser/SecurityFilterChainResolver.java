package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects Spring Security filter chain configuration and authorization topology.
 * Parses {@code @PreAuthorize}, {@code @Secured}, {@code @RolesAllowed}, and
 * {@code HttpSecurity} configurations to create security filter chain, auth rule,
 * and security flag nodes in the graph.
 */
@Slf4j
public class SecurityFilterChainResolver implements FrameworkResolver {

    private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed"
    );

    private final SpecterGraph graph;

    public SecurityFilterChainResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Spring Security Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        try (var files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(this::resolveFile);
        }
        log.info("Security analysis complete");
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());

                // Check class-level annotations
                for (var ann : cls.getAnnotations()) {
                    checkSecurityAnnotation(ann, "class:" + className, file.toString());
                }

                // Check method-level annotations
                for (MethodDeclaration method : cls.getMethods()) {
                    for (var ann : method.getAnnotations()) {
                        checkSecurityAnnotation(ann, "class:" + className, file.toString());
                    }
                }

                // Parse HttpSecurity configurations
                parseHttpSecurity(cls, className);
            });
        } catch (IOException e) {
            log.debug("Failed to parse for security: {}", file, e);
        }
    }

    private void checkSecurityAnnotation(com.github.javaparser.ast.expr.AnnotationExpr ann,
                                          String classNodeId, String sourceFile) {
        String annName = ann.getNameAsString();
        if (!SECURITY_ANNOTATIONS.contains(annName)) return;

        // Capture expression — previous code discarded the toString() result
        String[] expr = {""};
        if (ann instanceof SingleMemberAnnotationExpr sma) {
            expr[0] = sma.getMemberValue().toString().replaceAll("^\"|\"$", "");
        } else if (ann instanceof NormalAnnotationExpr na) {
            na.getPairs().stream()
                    .filter(p -> "value".equals(p.getNameAsString()))
                    .findFirst()
                    .ifPresent(p -> expr[0] = p.getValue().toString().replaceAll("^\"|\"$", ""));
        }
        String expression = expr[0];

        // classNodeId carries "class:" prefix but controllerClass metadata does not
        String rawClassName = classNodeId.startsWith("class:")
                ? classNodeId.substring("class:".length())
                : classNodeId;

        String ruleId = "auth_rule:" + rawClassName + "." + annName;
        SpecterNode ruleNode = SpecterNode.of(ruleId, annName + " on " + rawClassName, NodeType.AUTH_RULE)
                .withMetadata("annotation", annName)
                .withMetadata("expression", expression)
                .withMetadata("sourceFile", sourceFile);
        graph.addNode(ruleNode);

        graph.getNode(classNodeId).ifPresent(node ->
                graph.addEdge(node.id(), ruleId, EdgeType.SECURED_BY));

        // Secure any CONTROLLER_ENDPOINT belonging to this class
        graph.allNodes().stream()
                .filter(n -> n.type() == NodeType.CONTROLLER_ENDPOINT
                        && rawClassName.equals(n.metadata().get("controllerClass")))
                .forEach(ep -> graph.addEdge(ep.id(), ruleId, EdgeType.SECURED_BY));
    }


    private void parseHttpSecurity(ClassOrInterfaceDeclaration cls, String className) {
        // Look for SecurityFilterChain bean method patterns
        for (MethodDeclaration method : cls.getMethods()) {
            String body = method.getBody().map(b -> b.toString()).orElse("");
            if (!body.contains("SecurityFilterChain") && !body.contains("HttpSecurity")) continue;

            String chainId = "security_filter_chain:" + className + "." + method.getNameAsString();
            SpecterNode chainNode = SpecterNode.of(chainId,
                    "SecurityFilterChain-" + method.getNameAsString(),
                    NodeType.SECURITY_FILTER_CHAIN)
                    .withMetadata("configurationClass", className)
                    .withMetadata("method", method.getNameAsString());
            graph.addNode(chainNode);

            // Detect CSRF configuration
            if (body.contains(".csrf().disable()") || body.contains(".csrf(AbstractHttpConfigurer::disable)")) {
                String flagId = "security_flag:" + className + ".CSRF_DISABLED";
                SpecterNode flagNode = SpecterNode.of(flagId, "CSRF_DISABLED", NodeType.SECURITY_FLAG)
                        .withMetadata("flag", "CSRF_DISABLED")
                        .withMetadata("source", className);
                graph.addNode(flagNode);
                graph.addEdge(chainId, flagId, EdgeType.CONFIGURED_BY);
            }

            // Detect session management
            if (body.contains("STATELESS")) {
                String flagId = "security_flag:" + className + ".STATELESS";
                SpecterNode flagNode = SpecterNode.of(flagId, "STATELESS_SESSION", NodeType.SECURITY_FLAG)
                        .withMetadata("flag", "STATELESS");
                graph.addNode(flagNode);
                graph.addEdge(chainId, flagId, EdgeType.CONFIGURED_BY);
            }

            // Detect authorization URL matchers
            String[] lines = body.split("\n");
            for (String line : lines) {
                if (line.contains("requestMatchers") && line.contains("hasRole")) {
                    String flagId = "security_flag:" + className + ".AUTH_MATCHER_" + flagIdCounter.getAndIncrement();
                    SpecterNode flagNode = SpecterNode.of(flagId, "AuthMatcher", NodeType.SECURITY_FLAG)
                            .withMetadata("rule", line.trim());
                    graph.addNode(flagNode);
                    graph.addEdge(chainId, flagId, EdgeType.CONFIGURED_BY);
                }
            }

            graph.getNode("class:" + className).ifPresent(classNode ->
                    graph.addEdge(classNode.id(), chainId, EdgeType.CONFIGURED_BY));
        }
    }

    private final AtomicInteger flagIdCounter = new AtomicInteger(0);
}
