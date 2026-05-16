package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.specter.core.graph.*;
import com.specter.core.registry.BeanRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass 2 AOP proxy injection resolver. Runs after dependency resolution to
 * detect Spring cross-cutting annotations ({@code @Transactional},
 * {@code @Cacheable}, {@code @Async}, {@code @PreAuthorize}) and rewires
 * the {@code CALLS} edge topology so the graph reflects runtime interception.
 *
 * <p>Instead of:
 * <pre>{@code Controller → [CALLS] → Service}</pre>
 *
 * <p>The graph becomes:
 * <pre>{@code Controller → [CALLS] → ProxyNode → [CALLS] → Service}</pre>
 *
 * <p>This teaches downstream AI analysis that a transaction, cache, async
 * execution, or security check boundary exists between the caller and the
 * actual method execution.
 */
@Slf4j
public class AopProxyResolver implements FrameworkResolver {

    private static final Map<String, String> AOP_ANNOTATION_STEREOTYPES = createStereotypeMap();

    private static Map<String, String> createStereotypeMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Transactional", "TRANSACTION_INTERCEPTOR");
        map.put("Cacheable", "CACHE_INTERCEPTOR");
        map.put("CacheEvict", "CACHE_INTERCEPTOR");
        map.put("CachePut", "CACHE_INTERCEPTOR");
        map.put("Async", "ASYNC_INTERCEPTOR");
        map.put("PreAuthorize", "SECURITY_INTERCEPTOR");
        map.put("PostAuthorize", "SECURITY_INTERCEPTOR");
        map.put("Secured", "SECURITY_INTERCEPTOR");
        map.put("RolesAllowed", "SECURITY_INTERCEPTOR");
        map.put("Retryable", "RETRY_INTERCEPTOR");
        map.put("Recover", "RETRY_INTERCEPTOR");
        map.put("Scheduled", "SCHEDULED_INTERCEPTOR");
        map.put("Locked", "LOCK_INTERCEPTOR");
        return Collections.unmodifiableMap(map);
    }

    private final SpecterGraph graph;
    private final BeanRegistry registry;
    private final Map<String, Set<String>> classAopStereotypes = new HashMap<>();

    public AopProxyResolver(SpecterGraph graph, BeanRegistry registry) {
        this.graph = graph;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "AOP Proxy Rewiring";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        // Phase A: Discover which classes have AOP-annotated methods
        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(this::discoverAopAnnotations);
        }

        if (classAopStereotypes.isEmpty()) {
            log.info("No AOP-annotated methods found — proxy rewiring skipped");
            return;
        }

        log.info("Discovered {} classes with AOP stereotypes: {}", 
                classAopStereotypes.size(), classAopStereotypes.keySet());

        // Phase B: Rewire CALLS edges through proxy nodes
        rewireProxyEdges();
    }

    // ── Phase A: Discovery ───────────────────────────────────────────────

    private void discoverAopAnnotations(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());

                // Only scan active beans or all classes if no registry
                if (registry.size() > 0 && !registry.isActive(className)) {
                    return;
                }

                Set<String> stereotypes = new LinkedHashSet<>();
                for (MethodDeclaration method : cls.getMethods()) {
                    for (AnnotationExpr ann : method.getAnnotations()) {
                        String annName = ann.getNameAsString();
                        String stereotype = AOP_ANNOTATION_STEREOTYPES.get(annName);
                        if (stereotype != null) {
                            stereotypes.add(stereotype);
                        }
                    }

                    // Also check class-level annotations
                    for (AnnotationExpr ann : cls.getAnnotations()) {
                        String annName = ann.getNameAsString();
                        String stereotype = AOP_ANNOTATION_STEREOTYPES.get(annName);
                        if (stereotype != null) {
                            stereotypes.add(stereotype);
                        }
                    }
                }

                if (!stereotypes.isEmpty()) {
                    classAopStereotypes.put(className, stereotypes);
                }
            });
        } catch (IOException e) {
            log.debug("Failed to parse for AOP discovery: {}", file, e);
        }
    }

    // ── Phase B: Edge rewiring ───────────────────────────────────────────

    /**
     * Resolves the AOP stereotypes for a class name that may be a simple name
     * (from a CALLS edge variable scope) or a fully-qualified name.
     */
    private Set<String> findStereotypes(String className) {
        Set<String> direct = classAopStereotypes.get(className);
        if (direct != null) return direct;

        String simpleName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;
        String lowerSimple = simpleName.toLowerCase();

        return classAopStereotypes.entrySet().stream()
                .filter(e -> {
                    String fqn = e.getKey().toLowerCase();
                    return fqn.equals(lowerSimple)
                            || fqn.endsWith("." + lowerSimple);
                })
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private void rewireProxyEdges() {
        // Snapshot existing CALLS edges so we can safely modify
        List<SpecterEdge> callEdges = new ArrayList<>();
        for (SpecterEdge edge : graph.allEdges()) {
            if (edge.type() == EdgeType.CALLS) {
                callEdges.add(edge);
            }
        }

        Set<String> rewired = new HashSet<>();

        for (SpecterEdge callEdge : callEdges) {
            String targetId = callEdge.targetId();

            // Extract class name from node ID (strip "class:" prefix)
            if (!targetId.startsWith("class:")) continue;
            String targetClass = targetId.substring("class:".length());

            Set<String> stereotypes = findStereotypes(targetClass);
            if (stereotypes == null || stereotypes.isEmpty()) continue;

            String sourceId = callEdge.sourceId();
            String edgeKey = sourceId + "->" + targetId;

            if (!rewired.add(edgeKey)) continue;

            // Create a single proxy node per target class
            String proxyNodeId = "proxy:" + targetClass;
            String stereotypeTag = String.join(",", stereotypes);

            SpecterNode proxyNode = SpecterNode.of(proxyNodeId, targetClass + "$Proxy", NodeType.PROXY)
                    .withMetadata("PROXY_STEREOTYPE", stereotypeTag)
                    .withMetadata("targetClass", targetClass)
                    .withMetadata("stereotypes", stereotypeTag);
            graph.addNode(proxyNode);

            // Rewire: source → proxy (replaces source → target)
            // proxy → target (new interception edge)
            graph.addEdge(sourceId, proxyNodeId, EdgeType.CALLS);
            graph.addEdge(proxyNodeId, targetId, EdgeType.CALLS);

            log.debug("Rewired {} → {} through proxy {} [{}]",
                    sourceId, targetId, proxyNodeId, stereotypeTag);
        }

        log.info("AOP proxy rewiring complete — {} edges intercepted", rewired.size());
    }
}
