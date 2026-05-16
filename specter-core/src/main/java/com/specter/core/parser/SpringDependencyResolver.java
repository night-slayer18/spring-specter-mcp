package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.specter.core.graph.*;
import com.specter.core.registry.BeanRegistry;
import com.specter.core.registry.BeanRegistry.BeanMetadata;
import com.specter.core.registry.BeanRegistry.ConditionalMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass 2 dependency injection resolver. Uses the {@link BeanRegistry} from
 * Pass 1 to determine which beans are active, then resolves constructor
 * and field injection with {@code @Qualifier} / {@code @Primary}
 * disambiguation.
 *
 * <p>Only processes classes registered as active beans. Stores
 * {@code @ConditionalOn*} metadata as node traits so downstream tools
 * can reason about conditional wiring.
 */
@Slf4j
public class SpringDependencyResolver implements FrameworkResolver {

    private final SpecterGraph graph;
    private final BeanRegistry registry;
    private final CombinedTypeSolver typeSolver;

    public SpringDependencyResolver(SpecterGraph graph, BeanRegistry registry) {
        this.graph = graph;
        this.registry = registry;
        this.typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        StaticJavaParser.getParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));
    }

    public SpringDependencyResolver(SpecterGraph graph) {
        this(graph, new BeanRegistry());
    }

    @Override
    public String name() {
        return "Spring Dependency Injection";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        // If registry is empty (no Pass 1), fall back to scanning all files
        boolean useContextualScan = registry.size() > 0;

        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> resolveFile(file, useContextualScan));
        }
    }

    private void resolveFile(Path file, boolean useContextualScan) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());

                // Skip non-active beans when registry is populated
                if (useContextualScan && !registry.isActive(className)) {
                    return;
                }

                resolveClass(cls, className, file);
            });
        } catch (IOException e) {
            log.warn("Failed to parse file: {}", file, e);
        }
    }

    private void resolveClass(ClassOrInterfaceDeclaration cls, String className, Path file) {
        NodeType nodeType = classifyStereotype(cls);
        String nodeId = "class:" + className;

        // Build node with registry metadata if available
        SpecterNode classNode = SpecterNode.of(nodeId, className, nodeType)
                .withMetadata("sourceFile", file.toString());

        BeanMetadata meta = registry.getMetadata(className).orElse(null);
        if (meta != null) {
            classNode = applyBeanMetadata(classNode, meta);
        }

        graph.addNode(classNode);

        // Resolve constructor injection with @Qualifier support
        cls.findAll(ConstructorDeclaration.class).forEach(ctor ->
            resolveConstructorInjection(ctor, nodeId));

        // Resolve @Autowired field injection with @Qualifier support
        cls.findAll(FieldDeclaration.class).forEach(field ->
            resolveFieldInjection(field, nodeId));

        // Resolve method calls (these will later be rewired by AopProxyResolver)
        cls.getMethods().forEach(method ->
            method.findAll(MethodCallExpr.class).forEach(call -> {
                String scope = call.getScope()
                        .map(Object::toString)
                        .orElse("this");
                if (!"this".equals(scope)) {
                    String scopeClass = scope.contains(".")
                            ? scope.substring(0, scope.indexOf('.'))
                            : scope;
                    String targetNodeId = "class:" + scopeClass;
                    graph.addEdge(nodeId, targetNodeId, EdgeType.CALLS);
                }
            }));
    }

    // ── Constructor injection ────────────────────────────────────────────

    private void resolveConstructorInjection(ConstructorDeclaration ctor, String sourceNodeId) {
        ctor.getParameters().forEach(param -> {
            String paramType = param.getType().asString();
            String qualifier = extractQualifierValue(param.getAnnotations());
            String resolvedId = resolveTypeToNodeId(paramType, qualifier);

            if (resolvedId != null) {
                graph.addEdge(sourceNodeId, resolvedId, EdgeType.INJECTS);
                ensureNodeExists(resolvedId, paramType);
            }
        });
    }

    // ── Field injection ──────────────────────────────────────────────────

    private void resolveFieldInjection(FieldDeclaration field, String sourceNodeId) {
        field.getAnnotationByName("Autowired").ifPresent(autowired -> {
            String fieldType = field.getCommonType().asString();
            String qualifier = extractQualifierValue(field.getAnnotations());
            String resolvedId = resolveTypeToNodeId(fieldType, qualifier);

            if (resolvedId != null) {
                graph.addEdge(sourceNodeId, resolvedId, EdgeType.INJECTS);
                ensureNodeExists(resolvedId, fieldType);
            }
        });
    }

    // ── Type resolution with registry ────────────────────────────────────

    private String resolveTypeToNodeId(String typeName, String qualifier) {
        // Try BeanRegistry first (simulated Spring context)
        Optional<String> registryResult = registry.resolveInjectionTarget(typeName, qualifier);
        if (registryResult.isPresent()) {
            return "class:" + registryResult.get();
        }

        // Fall back to JavaSymbolSolver
        try {
            ResolvedReferenceTypeDeclaration resolved = typeSolver.solveType(typeName);
            return "class:" + resolved.getQualifiedName();
        } catch (Exception e) {
            // If registry exists but couldn't resolve, mark as unresolvable
            if (registry.size() > 0) {
                log.debug("Could not resolve injection target for type {} (qualifier: {})",
                        typeName, qualifier);
                return null;
            }
            // Without registry, fall back to the type name itself
            return "class:" + typeName;
        }
    }

    private void ensureNodeExists(String nodeId, String typeName) {
        if (graph.getNode(nodeId).isEmpty()) {
            graph.addNode(SpecterNode.of(nodeId, typeName, NodeType.SERVICE));
        }
    }

    // ── Metadata application ─────────────────────────────────────────────

    private SpecterNode applyBeanMetadata(SpecterNode node, BeanMetadata meta) {
        SpecterNode enriched = node;

        enriched = enriched.withMetadata("beanName", meta.beanName());

        if (meta.primary()) {
            enriched = enriched.withMetadata("PRIMARY", "TRUE");
        }
        if (meta.qualifier() != null && !meta.qualifier().isBlank()) {
            enriched = enriched.withMetadata("QUALIFIER", meta.qualifier());
        }
        if (!"singleton".equalsIgnoreCase(meta.scope())) {
            enriched = enriched.withMetadata("SCOPE", meta.scope());
        }

        if (!meta.activeProfiles().isEmpty()) {
            enriched = enriched.withMetadata("PROFILES",
                    String.join(",", meta.activeProfiles()));
        }

        for (ConditionalMetadata cond : meta.conditions()) {
            String traitKey = "CONDITION_" + cond.type().name();
            String traitValue = cond.key();
            if (cond.expectedValue() != null && !cond.expectedValue().isBlank()) {
                traitValue += "=" + cond.expectedValue();
            }
            enriched = enriched.withMetadata(traitKey, traitValue);
        }

        return enriched;
    }

    // ── Annotation helpers ───────────────────────────────────────────────

    private NodeType classifyStereotype(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .map(ann -> switch (ann.getNameAsString()) {
                    case "RestController", "Controller" -> NodeType.CONTROLLER;
                    case "Service" -> NodeType.SERVICE;
                    case "Repository" -> NodeType.REPOSITORY;
                    case "Component" -> NodeType.COMPONENT;
                    case "Configuration" -> NodeType.CONFIGURATION;
                    default -> null;
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(cls.isInterface() ? NodeType.INTERFACE : NodeType.CLASS);
    }

    private String extractQualifierValue(List<AnnotationExpr> annotations) {
        for (AnnotationExpr ann : annotations) {
            if ("Qualifier".equals(ann.getNameAsString())) {
                if (ann.isSingleMemberAnnotationExpr()) {
                    return ann.asSingleMemberAnnotationExpr()
                            .getMemberValue().toString().replaceAll("\"", "");
                }
                return ann.getNameAsString(); // @Qualifier without value
            }
        }
        return null;
    }
}
