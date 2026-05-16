package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass 2 resolver for GraalVM Native / Spring AOT compatibility analysis.
 * Detects patterns that require reflection hints, resource hints, or proxy
 * hints in native images — and flags Spring patterns incompatible with AOT.
 */
@Slf4j
public class GraalVmCompatibilityResolver implements FrameworkResolver {

    private static final Set<String> REFLECTION_METHODS = Set.of(
            "forName", "invoke", "getDeclaredMethod", "getDeclaredField", "newInstance"
    );
    private static final Set<String> RESOURCE_METHODS = Set.of(
            "getResourceAsStream", "getSystemResourceAsStream", "getResource"
    );
    private static final Set<String> PROXY_METHODS = Set.of(
            "newProxyInstance"
    );
    private static final Set<String> AOT_FRIENDLY_ANNOTATIONS = Set.of(
            "RegisterReflectionForBinding", "ImportRuntimeHints", "Reflective"
    );

    private final SpecterGraph graph;
    private final AtomicInteger issueCounter = new AtomicInteger(0);

    public GraalVmCompatibilityResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "GraalVM Compatibility Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        issueCounter.set(0);

        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(this::analyzeFile);
        }

        log.info("GraalVM compatibility analysis complete: {} issues found", issueCounter.get());
    }

    private void analyzeFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());
                String classNodeId = "class:" + className;

                analyzeReflection(cls, classNodeId);
                analyzeDynamicProxies(cls, classNodeId);
                analyzeResourceLoading(cls, classNodeId);
                analyzeAotAnnotations(cls, classNodeId);
                analyzeScopeCompatibility(cls, classNodeId);
                analyzeLazyInjection(cls, classNodeId);
            });
        } catch (IOException e) {
            log.debug("Failed to parse file for GraalVM analysis: {}", file, e);
        }
    }

    // ── Reflection detection ────────────────────────────────────────────

    private void analyzeReflection(ClassOrInterfaceDeclaration cls, String classNodeId) {
        boolean usesReflection = false;
        List<String> reflectionDetails = new ArrayList<>();

        for (MethodDeclaration method : cls.getMethods()) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String methodName = call.getNameAsString();
                if (REFLECTION_METHODS.contains(methodName)) {
                    usesReflection = true;
                    reflectionDetails.add(method.getNameAsString() + " calls " + methodName);
                }
            }
        }

        if (usesReflection) {
            graph.getNode(classNodeId).ifPresent(node -> {
                SpecterNode enriched = node
                        .withMetadata("usesReflection", "true")
                        .withMetadata("reflectionDetails", String.join(", ", reflectionDetails));
                graph.addNode(enriched);
            });

            createIssueNode(classNodeId, "Uses reflection without explicit AOT hints",
                    "Add @RegisterReflectionForBinding on the target type, " +
                            "or use @ImportRuntimeHints to register the reflective access.");
        }
    }

    // ── Dynamic proxy detection ─────────────────────────────────────────

    private void analyzeDynamicProxies(ClassOrInterfaceDeclaration cls, String classNodeId) {
        boolean createsProxy = false;
        List<String> proxyDetails = new ArrayList<>();

        for (MethodDeclaration method : cls.getMethods()) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (PROXY_METHODS.contains(call.getNameAsString())) {
                    createsProxy = true;
                    proxyDetails.add(method.getNameAsString() + " creates dynamic proxy: "
                            + call.getNameAsString());
                }
            }
        }

        if (createsProxy) {
            graph.getNode(classNodeId).ifPresent(node -> {
                SpecterNode enriched = node
                        .withMetadata("createsDynamicProxy", "true")
                        .withMetadata("proxyDetails", String.join(", ", proxyDetails));
                graph.addNode(enriched);
            });

            createIssueNode(classNodeId, "Creates dynamic proxies",
                    "Dynamic proxies require --add-opens flags or a " +
                            "proxy-config.json entry for native image.");
        }
    }

    // ── Resource loading detection ──────────────────────────────────────

    private void analyzeResourceLoading(ClassOrInterfaceDeclaration cls, String classNodeId) {
        boolean loadsResources = false;
        List<String> resourceDetails = new ArrayList<>();

        for (MethodDeclaration method : cls.getMethods()) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (RESOURCE_METHODS.contains(call.getNameAsString())) {
                    loadsResources = true;
                    String resourcePath = call.getArguments().stream()
                            .filter(a -> a instanceof StringLiteralExpr)
                            .map(a -> ((StringLiteralExpr) a).asString())
                            .findFirst()
                            .orElse("unknown");
                    resourceDetails.add(method.getNameAsString()
                            + " loads resource: " + resourcePath);
                }
            }
        }

        if (loadsResources) {
            graph.getNode(classNodeId).ifPresent(node -> {
                SpecterNode enriched = node
                        .withMetadata("loadsResources", "true")
                        .withMetadata("resourceDetails", String.join(", ", resourceDetails));
                graph.addNode(enriched);
            });

            createIssueNode(classNodeId, "Loads classpath resources",
                    "Resources loaded via classpath must be declared in " +
                            "resource-config.json for native image inclusion.");
        }
    }

    // ── AOT-friendly annotations ────────────────────────────────────────

    private void analyzeAotAnnotations(ClassOrInterfaceDeclaration cls, String classNodeId) {
        boolean hasAotHints = false;
        List<String> aotAnnotations = new ArrayList<>();

        for (AnnotationExpr ann : cls.getAnnotations()) {
            String annName = ann.getNameAsString();
            if (AOT_FRIENDLY_ANNOTATIONS.contains(annName)) {
                hasAotHints = true;
                aotAnnotations.add(annName);
            }
        }

        if (hasAotHints) {
            graph.getNode(classNodeId).ifPresent(node -> {
                SpecterNode enriched = node
                        .withMetadata("hasAotHints", "true")
                        .withMetadata("aotAnnotations", String.join(", ", aotAnnotations));
                graph.addNode(enriched);
            });
        }
    }

    // ── Scope compatibility ─────────────────────────────────────────────

    private void analyzeScopeCompatibility(ClassOrInterfaceDeclaration cls, String classNodeId) {
        boolean hasNonSingletonScope = cls.getAnnotations().stream()
                .anyMatch(a -> Set.of("SessionScope", "RequestScope",
                        "PrototypeScope").contains(a.getNameAsString()));

        if (hasNonSingletonScope) {
            graph.getNode(classNodeId).ifPresent(node -> {
                SpecterNode enriched = node
                        .withMetadata("nativeRiskScope", "true");
                graph.addNode(enriched);
            });

            createIssueNode(classNodeId, "Non-singleton scope in AOT context",
                    "Request/session scoped beans require proxy support that " +
                            "may not work in native images. Consider using " +
                            "singleton or custom scope configuration.");
        }
    }

    // ── @Lazy injection detection ───────────────────────────────────────

    private void analyzeLazyInjection(ClassOrInterfaceDeclaration cls, String classNodeId) {
        boolean usesLazy = false;

        for (FieldDeclaration field : cls.getFields()) {
            if (field.getAnnotationByName("Lazy").isPresent()) {
                usesLazy = true;
                break;
            }
        }

        if (!usesLazy) {
            for (MethodDeclaration method : cls.getMethods()) {
                if (method.getAnnotationByName("Lazy").isPresent()
                        || method.getParameterByType("Lazy").isPresent()) {
                    usesLazy = true;
                    break;
                }
            }
        }

        if (usesLazy) {
            graph.getNode(classNodeId).ifPresent(node -> {
                SpecterNode enriched = node
                        .withMetadata("nativeRiskLazy", "true");
                graph.addNode(enriched);
            });

            createIssueNode(classNodeId, "Uses @Lazy injection",
                    "@Lazy may cause issues in native images due to " +
                            "altering bean initialization order. " +
                            "Test thoroughly in native mode.");
        }
    }

    // ── Issue creation ──────────────────────────────────────────────────

    private void createIssueNode(String classNodeId, String issue, String recommendation) {
        int issueId = issueCounter.getAndIncrement();
        String issueNodeId = "compat_issue:graalvm:" + issueId;

        graph.getNode(classNodeId).map(SpecterNode::name).ifPresent(className -> {
            SpecterNode issueNode = SpecterNode.of(issueNodeId,
                            className + " — " + issue.substring(0, Math.min(40, issue.length())),
                            NodeType.COMPATIBILITY_ISSUE)
                    .withMetadata("className", className)
                    .withMetadata("issue", issue)
                    .withMetadata("recommendation", recommendation)
                    .withMetadata("issueId", String.valueOf(issueId));
            graph.addNode(issueNode);
            graph.addEdge(classNodeId, issueNodeId, EdgeType.COMPATIBILITY_RISK);
        });
    }
}
