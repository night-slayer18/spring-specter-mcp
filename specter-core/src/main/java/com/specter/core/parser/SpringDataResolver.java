package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Universal Spring Data resolver. Detects any interface extending a Spring Data
 * {@code Repository} variant (JPA, MongoDB, Cassandra, JDBC, etc.) and maps
 * persistence entities via generic type resolution and standard annotations
 * ({@code @Entity}, {@code @Table}, {@code @Document}, {@code @Id}).
 *
 * <p>Produces {@link NodeType#DATA_REPOSITORY} and {@link NodeType#PERSISTENCE_ENTITY}
 * nodes with technology-specific metadata, replacing the old technology-locked
 * {@code CassandraModelResolver}.
 */
@Slf4j
public class SpringDataResolver implements FrameworkResolver {

    private static final Set<String> PERSISTENCE_ANNOTATIONS = Set.of(
            "Entity", "Table", "Document", "Persistent"
    );
    private static final Set<String> ID_ANNOTATIONS = Set.of(
            "Id", "EmbeddedId", "IdClass"
    );
    private static final Set<String> CONVERTER_ANNOTATIONS = Set.of(
            "Converter", "WritingConverter", "ReadingConverter"
    );

    private final SpecterGraph graph;

    public SpringDataResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Spring Data Persistence";
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
                if (cls.isInterface()) {
                    processSpringDataRepository(cls, file);
                }
                processPersistenceEntity(cls, file);
                processDataConverter(cls, file);
            }
        } catch (IOException e) {
            log.warn("Failed to parse file for Spring Data resolution: {}", file, e);
        }
    }

    // ── Repository detection ──────────────────────────────────────────────

    private void processSpringDataRepository(ClassOrInterfaceDeclaration repoInterface, Path file) {
        for (ClassOrInterfaceType extended : repoInterface.getExtendedTypes()) {
            if (!isSpringDataRepository(extended)) continue;

            String repoName = repoInterface.getFullyQualifiedName()
                    .orElse(repoInterface.getNameAsString());
            String repoNodeId = "data_repo:" + repoName;
            String technology = classifyPersistenceTechnology(extended);

            SpecterNode repoNode = SpecterNode.of(repoNodeId, repoName, NodeType.DATA_REPOSITORY)
                    .withMetadata("sourceFile", file.toString())
                    .withMetadata("technology", technology);
            graph.addNode(repoNode);

            var typeArgs = extended.getTypeArguments().orElse(null);
            if (typeArgs != null && !typeArgs.isEmpty()) {
                String entityType = typeArgs.get(0).asString();
                String entityNodeId = "persistence_entity:" + entityType;

                SpecterNode entityNode = SpecterNode.of(entityNodeId, entityType, NodeType.PERSISTENCE_ENTITY)
                        .withMetadata("repository", repoName)
                        .withMetadata("technology", technology)
                        .withMetadata("sourceFile", file.toString());
                graph.addNode(entityNode);

                graph.addEdge(repoNodeId, entityNodeId, EdgeType.MAPS_TO);
            }
        }
    }

    private boolean isSpringDataRepository(ClassOrInterfaceType type) {
        String name = type.getNameAsString();
        return name.endsWith("Repository") || name.equals("Repository")
                || name.equals("CrudRepository") || name.equals("PagingAndSortingRepository")
                || name.equals("ReactiveCrudRepository") || name.equals("ReactiveSortingRepository")
                || name.equals("RxJava3CrudRepository") || name.equals("CoroutineCrudRepository");
    }

    private String classifyPersistenceTechnology(ClassOrInterfaceType type) {
        String name = type.getNameAsString();
        if (name.contains("Jpa") || name.contains("JPA")) return "jpa";
        if (name.contains("Mongo") || name.contains("MongoDB")) return "mongodb";
        if (name.contains("Cassandra")) return "cassandra";
        if (name.contains("Redis")) return "redis";
        if (name.contains("Jdbc") || name.contains("JDBC")) return "jdbc";
        if (name.contains("Elasticsearch")) return "elasticsearch";
        if (name.contains("Neo4j")) return "neo4j";
        if (name.contains("Couchbase")) return "couchbase";
        if (name.contains("DynamoDB")) return "dynamodb";
        if (name.contains("R2dbc") || name.contains("R2DBC")) return "r2dbc";
        return "generic";
    }

    // ── Entity detection ───────────────────────────────────────────────────

    private void processPersistenceEntity(ClassOrInterfaceDeclaration cls, Path file) {
        String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
        boolean hasPersistenceAnnotation = cls.getAnnotations().stream()
                .anyMatch(a -> PERSISTENCE_ANNOTATIONS.contains(a.getNameAsString()));
        boolean hasIdAnnotation = cls.getFields().stream()
                .flatMap(f -> f.getAnnotations().stream())
                .anyMatch(a -> ID_ANNOTATIONS.contains(a.getNameAsString()));
        boolean hasIdMethod = cls.getMethods().stream()
                .flatMap(m -> m.getAnnotations().stream())
                .anyMatch(a -> ID_ANNOTATIONS.contains(a.getNameAsString()));

        if (!hasPersistenceAnnotation && !(hasIdAnnotation || hasIdMethod)) return;

        String entityNodeId = "persistence_entity:" + className;
        boolean existing = graph.getNode(entityNodeId).isPresent();

        if (!existing) {
            String technology = detectTechnologyFromAnnotations(cls);
            SpecterNode entityNode = SpecterNode.of(entityNodeId, className, NodeType.PERSISTENCE_ENTITY)
                    .withMetadata("sourceFile", file.toString())
                    .withMetadata("technology", technology);
            graph.addNode(entityNode);
        }

        resolveRelationalEdges(cls, className, entityNodeId);
    }

    /**
     * Scans entity fields for {@code @OneToMany} and {@code @ManyToOne}
     * annotations, drawing {@link EdgeType#HAS_MANY} and
     * {@link EdgeType#BELONGS_TO} edges to the target entity nodes.
     */
    private void resolveRelationalEdges(ClassOrInterfaceDeclaration cls,
                                         String sourceClassName,
                                         String sourceNodeId) {
        for (FieldDeclaration field : cls.getFields()) {
            for (VariableDeclarator var : field.getVariables()) {
                for (AnnotationExpr ann : field.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    EdgeType edgeType = switch (annName) {
                        case "OneToMany" -> EdgeType.HAS_MANY;
                        case "ManyToOne" -> EdgeType.BELONGS_TO;
                        default -> null;
                    };
                    if (edgeType == null) continue;

                    String targetType = extractTargetType(var);
                    if (targetType == null) {
                        targetType = extractMappedByTarget(ann);
                    }
                    if (targetType == null) continue;

                    String targetNodeId = "persistence_entity:" + targetType;
                    if (edgeType == EdgeType.HAS_MANY) {
                        graph.addEdge(sourceNodeId, targetNodeId, EdgeType.HAS_MANY);
                    } else {
                        graph.addEdge(sourceNodeId, targetNodeId, EdgeType.BELONGS_TO);
                    }
                }
            }
        }
    }

    private String extractTargetType(VariableDeclarator var) {
        String typeName = var.getTypeAsString();
        // Strip collection/generic wrappers: List<Order> → Order
        int angleStart = typeName.indexOf('<');
        int angleEnd = typeName.lastIndexOf('>');
        if (angleStart >= 0 && angleEnd > angleStart) {
            typeName = typeName.substring(angleStart + 1, angleEnd).trim();
        }
        // Strip array suffix
        if (typeName.endsWith("[]")) {
            typeName = typeName.substring(0, typeName.length() - 2);
        }
        return typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
    }

    private String extractMappedByTarget(AnnotationExpr ann) {
        if (!ann.isNormalAnnotationExpr()) return null;
        for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
            if ("mappedBy".equals(pair.getNameAsString())) {
                return pair.getValue().asStringLiteralExpr().asString();
            }
        }
        return null;
    }

    private String detectTechnologyFromAnnotations(ClassOrInterfaceDeclaration cls) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("Document".equals(name)) return "mongodb";
            if ("Entity".equals(name)) return "jpa";
            if ("Table".equals(name)) return "jpa";
            if ("Persistent".equals(name)) return "jdo";
        }
        return "unknown";
    }

    // ── Converter detection ────────────────────────────────────────────────

    private void processDataConverter(ClassOrInterfaceDeclaration cls, Path file) {
        boolean isConverter = cls.getAnnotations().stream()
                .anyMatch(a -> CONVERTER_ANNOTATIONS.contains(a.getNameAsString()));

        if (!isConverter) {
            isConverter = cls.getImplementedTypes().stream()
                    .anyMatch(t -> "Converter".equals(t.getNameAsString())
                            || "AttributeConverter".equals(t.getNameAsString()));
        }

        if (!isConverter) return;

        String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
        String nodeId = "data_converter:" + className;

        SpecterNode converterNode = SpecterNode.of(nodeId, className, NodeType.DATA_CONVERTER)
                .withMetadata("sourceFile", file.toString());
        graph.addNode(converterNode);
    }
}
