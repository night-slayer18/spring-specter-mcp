package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass 2 performance anti-pattern resolver. Detects common JPA/Spring
 * performance issues: N+1 query hotspots, missing @Transactional on
 * multi-step operations, fetch strategy gaps on @OneToMany, and
 * @Cacheable usage gaps on hot read paths.
 */
@Slf4j
public class PerformancePatternResolver implements FrameworkResolver {

    private static final Set<String> REPOSITORY_METHOD_PREFIXES = Set.of(
            "find", "findAll", "get", "read", "load", "query",
            "save", "update", "delete", "remove",
            "count", "exists", "search"
    );

    private final SpecterGraph graph;

    // Class-level tracking across files
    private final Set<String> entityClassNames = new HashSet<>();
    private final Map<String, Set<String>> serviceReturnTypes = new HashMap<>();
    private final Map<String, Integer> serviceCallCount = new HashMap<>();

    public PerformancePatternResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Performance Pattern Detector";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        entityClassNames.clear();
        serviceReturnTypes.clear();
        serviceCallCount.clear();

        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(this::resolveFile);
        }

        resolveCacheableGaps();
        log.info("Performance pattern detection complete");
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());
                String nodeId = "class:" + className;

                detectEntityType(cls, className);
                detectNPlusOne(cls, className, nodeId);
                detectFetchStrategyIssues(cls, className, nodeId);
                detectMissingTransactional(cls, className, nodeId);
                detectCacheableGapsPerClass(cls, className);
            });
        } catch (IOException e) {
            log.debug("Failed to parse for performance: {}", file, e);
        }
    }

    // ── Entity type detection ───────────────────────────────────────────

    private void detectEntityType(ClassOrInterfaceDeclaration cls, String className) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String name = ann.getNameAsString();
            if (name.equals("Entity") || name.equals("Table") || name.equals("Document")) {
                entityClassNames.add(className);
                return;
            }
        }
    }

    // ── N+1 Query Detection ─────────────────────────────────────────────

    private void detectNPlusOne(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        boolean isServiceController = hasSpringStereotype(cls, "Service")
                || hasSpringStereotype(cls, "Component")
                || cls.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("RestController")
                            || a.getNameAsString().equals("Controller"));

        if (!isServiceController) return;

        for (MethodDeclaration method : cls.getMethods()) {
            if (!hasAnnotation(method, "Transactional")) continue;

            List<LoopContext> loops = collectLoops(method);
            for (LoopContext loop : loops) {
                List<String> repoCalls = collectRepositoryCalls(loop.body());
                if (repoCalls.size() >= 1) {
                    String perfNodeId = "perf:n_plus_one:" + className + "." + method.getNameAsString();
                    SpecterNode issueNode = SpecterNode.of(perfNodeId,
                            "N+1 Query Risk: " + className + "." + method.getNameAsString(),
                            NodeType.PERFORMANCE_ISSUE)
                            .withMetadata("className", className)
                            .withMetadata("methodName", method.getNameAsString())
                            .withMetadata("antiPattern", "N+1_QUERY")
                            .withMetadata("loopType", loop.type())
                            .withMetadata("repositoryCallsInLoop", String.join(",", repoCalls))
                            .withMetadata("severity", "HIGH");
                    graph.addNode(issueNode);
                    graph.addEdge(nodeId, perfNodeId, EdgeType.PERFORMANCE_RISK);

                    log.debug("N+1 risk: {} at {}::{} — {} repo calls in {} loop",
                            perfNodeId, className, method.getNameAsString(),
                            repoCalls.size(), loop.type());
                }
            }
        }
    }

    private boolean hasSpringStereotype(ClassOrInterfaceDeclaration cls, String stereotype) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(stereotype));
    }

    private boolean hasAnnotation(MethodDeclaration method, String annotationName) {
        return method.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(annotationName));
    }

    private record LoopContext(String type, Statement body) {}

    private List<LoopContext> collectLoops(MethodDeclaration method) {
        List<LoopContext> loops = new ArrayList<>();
        if (method.getBody().isEmpty()) return loops;

        method.getBody().get().walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof ForStmt forStmt) {
                loops.add(new LoopContext("for", forStmt.getBody()));
            } else if (node instanceof ForEachStmt forEachStmt) {
                loops.add(new LoopContext("foreach", forEachStmt.getBody()));
            } else if (node instanceof WhileStmt whileStmt) {
                loops.add(new LoopContext("while", whileStmt.getBody()));
            } else if (node instanceof DoStmt doStmt) {
                loops.add(new LoopContext("do-while", doStmt.getBody()));
            }
        });

        return loops;
    }

    private List<String> collectRepositoryCalls(Statement body) {
        List<String> calls = new ArrayList<>();
        body.walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof MethodCallExpr mce) {
                String methodName = mce.getNameAsString();
                String scope = mce.getScope().map(Object::toString).orElse("");

                for (String prefix : REPOSITORY_METHOD_PREFIXES) {
                    if (methodName.startsWith(prefix) && isRepositoryField(scope)) {
                        calls.add(mce.toString());
                        break;
                    }
                }
            }
        });
        return calls;
    }

    private boolean isRepositoryField(String scope) {
        return scope.endsWith("Repository") || scope.contains("repository")
                || scope.endsWith("Repo") || scope.endsWith("Dao")
                || scope.endsWith("DAO");
    }

    // ── Fetch Strategy Issues ───────────────────────────────────────────

    private void detectFetchStrategyIssues(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        boolean isEntity = cls.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Entity")
                        || a.getNameAsString().equals("Document"));

        if (!isEntity) return;

        for (FieldDeclaration field : cls.getFields()) {
            for (com.github.javaparser.ast.body.VariableDeclarator var : field.getVariables()) {
                boolean hasOneToMany = field.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("OneToMany"));
                if (!hasOneToMany) continue;

                boolean hasExplicitFetch = hasAnnotationAttribute(field, "OneToMany", "fetch");

                if (!hasExplicitFetch) {
                    String perfNodeId = "perf:fetch_strategy:" + className + "." + var.getNameAsString();
                    SpecterNode issueNode = SpecterNode.of(perfNodeId,
                            "Lazy fetch: " + className + "." + var.getNameAsString(),
                            NodeType.PERFORMANCE_ISSUE)
                            .withMetadata("className", className)
                            .withMetadata("fieldName", var.getNameAsString())
                            .withMetadata("antiPattern", "FETCH_STRATEGY")
                            .withMetadata("issue", "default LAZY fetch — potential LazyInitializationException")
                            .withMetadata("severity", "MEDIUM");
                    graph.addNode(issueNode);
                    graph.addEdge(nodeId, perfNodeId, EdgeType.PERFORMANCE_RISK);

                    // Mark the existing entity node with metadata
                    graph.getNode(nodeId).ifPresent(entityNode ->
                            graph.addNode(entityNode.withMetadata("lazyLoadingMissing", "true")));
                }
            }
        }
    }

    private boolean hasAnnotationAttribute(FieldDeclaration field, String annName, String attrName) {
        for (AnnotationExpr ann : field.getAnnotations()) {
            if (!ann.getNameAsString().equals(annName)) continue;
            if (ann instanceof NormalAnnotationExpr na) {
                return na.getPairs().stream()
                        .anyMatch(p -> p.getNameAsString().equals(attrName));
            }
            if (ann instanceof SingleMemberAnnotationExpr) {
                // Single member means the attribute is "value", not "fetch"
                return attrName.equals("value");
            }
        }
        return false;
    }

    // ── Missing @Transactional ──────────────────────────────────────────

    private void detectMissingTransactional(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        if (!hasSpringStereotype(cls, "Service")) return;

        for (MethodDeclaration method : cls.getMethods()) {
            if (hasAnnotation(method, "Transactional")) continue;

            Set<String> repoCalls = findRepositorySavesAndUpdates(method);
            if (repoCalls.size() >= 2) {
                String perfNodeId = "perf:missing_transaction:" + className + "." + method.getNameAsString();
                SpecterNode issueNode = SpecterNode.of(perfNodeId,
                        "Missing @Transactional: " + className + "." + method.getNameAsString(),
                        NodeType.PERFORMANCE_ISSUE)
                        .withMetadata("className", className)
                        .withMetadata("methodName", method.getNameAsString())
                        .withMetadata("antiPattern", "MISSING_TRANSACTION")
                        .withMetadata("repositoryOperations", String.join(",", repoCalls))
                        .withMetadata("severity", "HIGH");
                graph.addNode(issueNode);
                graph.addEdge(nodeId, perfNodeId, EdgeType.PERFORMANCE_RISK);
            }
        }
    }

    private Set<String> findRepositorySavesAndUpdates(MethodDeclaration method) {
        Set<String> ops = new LinkedHashSet<>();
        if (method.getBody().isEmpty()) return ops;

        method.getBody().get().walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof MethodCallExpr mce) {
                String methodName = mce.getNameAsString();
                String scope = mce.getScope().map(Object::toString).orElse("");
                if (isRepositoryField(scope)
                        && (methodName.startsWith("save")
                            || methodName.startsWith("update")
                            || methodName.startsWith("delete")
                            || methodName.startsWith("remove")
                            || methodName.startsWith("insert")
                            || methodName.equals("persist"))) {
                    ops.add(mce.toString());
                }
            }
        });
        return ops;
    }

    // ── @Cacheable gap detection ────────────────────────────────────────

    private void detectCacheableGapsPerClass(ClassOrInterfaceDeclaration cls, String className) {
        if (!hasSpringStereotype(cls, "Service")) return;

        for (MethodDeclaration method : cls.getMethods()) {
            if (hasAnnotation(method, "Cacheable")
                    || hasAnnotation(method, "CachePut")
                    || hasAnnotation(method, "CacheEvict")) {
                continue;
            }

            String returnType = method.getTypeAsString();
            boolean returnsEntity = entityClassNames.stream()
                    .anyMatch(e -> returnType.contains(e)
                            || returnType.endsWith("." + e));
            boolean isCollectionReturn = returnType.startsWith("List<")
                    || returnType.startsWith("Set<")
                    || returnType.startsWith("Collection<")
                    || returnType.startsWith("Optional<");

            if (returnsEntity || isCollectionReturn) {
                String methodKey = className + "." + method.getNameAsString();
                serviceReturnTypes.computeIfAbsent(methodKey, k -> new LinkedHashSet<>())
                        .add(returnType);
                serviceCallCount.put(methodKey, 0);
            }
        }
    }

    private void resolveCacheableGaps() {
        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() != EdgeType.CALLS) continue;
            String targetId = edge.targetId();
            if (!targetId.startsWith("class:")) continue;
            String targetClass = targetId.substring("class:".length());

            for (var entry : serviceReturnTypes.entrySet()) {
                String methodKey = entry.getKey();
                int dotIdx = methodKey.lastIndexOf('.');
                if (dotIdx < 0) continue;
                String svcClass = methodKey.substring(0, dotIdx);
                if (targetClass.equals(svcClass) || targetClass.endsWith("." + svcClass)) {
                    serviceCallCount.merge(methodKey, 1, Integer::sum);
                }
            }
        }

        for (var entry : serviceCallCount.entrySet()) {
            if (entry.getValue() >= 2) {
                String methodKey = entry.getKey();
                int dotIdx = methodKey.lastIndexOf('.');
                String className = methodKey.substring(0, dotIdx);
                String methodName = methodKey.substring(dotIdx + 1);

                String hintNodeId = "perf:cacheable_hint:" + methodKey;
                SpecterNode hintNode = SpecterNode.of(hintNodeId,
                        "Cacheable candidate: " + methodKey,
                        NodeType.PERFORMANCE_HINT)
                        .withMetadata("className", className)
                        .withMetadata("methodName", methodName)
                        .withMetadata("antiPattern", "MISSING_CACHEABLE")
                        .withMetadata("callCount", String.valueOf(entry.getValue()))
                        .withMetadata("returnType", String.join(",",
                                serviceReturnTypes.getOrDefault(methodKey, Set.of())))
                        .withMetadata("severity", "LOW");
                graph.addNode(hintNode);

                String classNodeId = "class:" + className;
                if (graph.getNode(classNodeId).isPresent()) {
                    graph.addEdge(classNodeId, hintNodeId, EdgeType.PERFORMANCE_RISK);
                }
            }
        }
    }
}
