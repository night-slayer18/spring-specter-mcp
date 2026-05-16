package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass 2 schema resolver. Parses Flyway SQL migrations and Liquibase
 * changelogs to construct a graph of tables, columns, and migration
 * history, then correlates those schema artifacts with JPA entities
 * via MAPS_TO edges.
 */
@Slf4j
public class DatabaseSchemaResolver implements FrameworkResolver {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\"`]?(\\w+)[\"`]?\\s*\\((.*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_ADD_COLUMN = Pattern.compile(
            "ALTER\\s+TABLE\\s+[\"`]?(\\w+)[\"`]?\\s+ADD\\s+(?:COLUMN\\s+)?[\"`]?(\\w+)[\"`]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_DEF = Pattern.compile(
            "\"?([a-zA-Z_]\\w*)\"?\\s+(\\w+(?:\\([^)]*\\))?)",
            Pattern.CASE_INSENSITIVE);

    // Liquibase XML element names
    private static final Pattern XML_CREATE_TABLE = Pattern.compile(
            "<createTable\\s+[^>]*tableName\\s*=\\s*\"(\\w+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_COLUMN = Pattern.compile(
            "<column\\s+[^>]*name\\s*=\\s*\"(\\w+)\"\\s*type\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_ADD_COLUMN = Pattern.compile(
            "<addColumn\\s+[^>]*tableName\\s*=\\s*\"(\\w+)\"[^>]*>.*?</addColumn>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final SpecterGraph graph;

    // Tracked across files
    private final List<MigrationEntry> migrations = new ArrayList<>();
    private final Map<String, String> tableToMigration = new LinkedHashMap<>();
    private final Map<String, Set<String>> tableColumns = new LinkedHashMap<>();
    private final Set<String> entityTableNames = new LinkedHashSet<>();
    private final Map<String, String> entityClassToTable = new LinkedHashMap<>();

    public DatabaseSchemaResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Database Schema Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        migrations.clear();
        tableToMigration.clear();
        tableColumns.clear();
        entityTableNames.clear();
        entityClassToTable.clear();

        Path resourcesRoot = sourceRoot.resolve("src/main/resources");
        if (Files.exists(resourcesRoot)) {
            parseFlywayMigrations(resourcesRoot.resolve("db/migration"));
            parseLiquibaseChangelogs(resourcesRoot.resolve("db/changelog"));
        }

        createSchemaGraph();
        correlateEntities(sourceRoot);
        log.info("Database schema resolution complete: {} tables from {} migrations",
                tableToMigration.size(), migrations.size());
    }

    // ── Flyway SQL parsing ──────────────────────────────────────────────

    private void parseFlywayMigrations(Path migrationDir) throws IOException {
        if (!Files.exists(migrationDir)) return;

        try (Stream<Path> files = Files.list(migrationDir).sorted()) {
            files.filter(f -> f.getFileName().toString().matches("V\\d+.*\\.sql"))
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        String version = fileName.replaceAll("V(\\d+).*", "$1");
                        parseSqlMigration(file, version, fileName);
                    });
        }
    }

    private void parseSqlMigration(Path file, String version, String fileName) {
        try {
            String content = Files.readString(file);
            String migrationId = "schema_migration:" + version;

            MigrationEntry entry = new MigrationEntry(version, fileName,
                    "flyway", migrationId);
            migrations.add(entry);

            Matcher tableMatcher = CREATE_TABLE.matcher(content);
            while (tableMatcher.find()) {
                String tableName = tableMatcher.group(1).toLowerCase();
                String body = tableMatcher.group(2);
                tableToMigration.putIfAbsent(tableName, migrationId);
                entry.tables().add(tableName);

                Set<String> cols = new LinkedHashSet<>();
                Matcher colMatcher = COLUMN_DEF.matcher(body);
                while (colMatcher.find()) {
                    String colName = colMatcher.group(1);
                    if (!colName.equalsIgnoreCase("PRIMARY")
                            && !colName.equalsIgnoreCase("FOREIGN")
                            && !colName.equalsIgnoreCase("UNIQUE")
                            && !colName.equalsIgnoreCase("CONSTRAINT")
                            && !colName.equalsIgnoreCase("INDEX")
                            && !colName.equalsIgnoreCase("KEY")) {
                        cols.add(colName);
                        entry.columns().add(tableName + "." + colName);
                    }
                }
                tableColumns.put(tableName, cols);
            }

            Matcher alterMatcher = ALTER_ADD_COLUMN.matcher(content);
            while (alterMatcher.find()) {
                String tableName = alterMatcher.group(1).toLowerCase();
                String colName = alterMatcher.group(2);
                entry.columns().add(tableName + "." + colName);
                tableColumns.computeIfAbsent(tableName, k -> new LinkedHashSet<>()).add(colName);
            }

            Matcher addColMatcher = XML_ADD_COLUMN.matcher(content);
            while (addColMatcher.find()) {
                String tableName = addColMatcher.group(1).toLowerCase();
                tableToMigration.putIfAbsent(tableName, migrationId);
                entry.tables().add(tableName);
                // Extract column names within <addColumn> blocks
                String addColBlock = addColMatcher.group(0);
                Matcher innerCol = XML_COLUMN.matcher(addColBlock);
                while (innerCol.find()) {
                    entry.columns().add(tableName + "." + innerCol.group(1));
                    tableColumns.computeIfAbsent(tableName, k -> new LinkedHashSet<>())
                            .add(innerCol.group(1));
                }
            }

        } catch (IOException e) {
            log.debug("Failed to parse Flyway migration: {}", file, e);
        }
    }

    // ── Liquibase parsing ───────────────────────────────────────────────

    private void parseLiquibaseChangelogs(Path changelogDir) throws IOException {
        if (!Files.exists(changelogDir)) return;

        try (Stream<Path> files = Files.walk(changelogDir)) {
            files.filter(f -> f.getFileName().toString().matches(".*\\.(xml|yaml|yml)"))
                    .forEach(file -> {
                        String fileName = file.getFileName().toString();
                        if (fileName.endsWith(".xml")) {
                            parseLiquibaseXml(file);
                        }
                    });
        }
    }

    private void parseLiquibaseXml(Path file) {
        try {
            String content = Files.readString(file);
            String migrationId = "schema_migration:" + file.getFileName().toString()
                    .replace(".xml", "");

            MigrationEntry entry = new MigrationEntry(
                    file.getFileName().toString(), file.getFileName().toString(),
                    "liquibase", migrationId);
            migrations.add(entry);

            Matcher createTableMatcher = XML_CREATE_TABLE.matcher(content);
            while (createTableMatcher.find()) {
                String tableName = createTableMatcher.group(1).toLowerCase();
                tableToMigration.putIfAbsent(tableName, migrationId);
                entry.tables().add(tableName);
            }

            Matcher colMatcher = XML_COLUMN.matcher(content);
            while (colMatcher.find()) {
                String colName = colMatcher.group(1);
                String colType = colMatcher.group(2);
                // Loose association — we don't know which table in XML without more context
                entry.columns().add("unknown." + colName + "(" + colType + ")");
            }

        } catch (IOException e) {
            log.debug("Failed to parse Liquibase changelog: {}", file, e);
        }
    }

    // ── Graph construction ──────────────────────────────────────────────

    private void createSchemaGraph() {
        for (MigrationEntry entry : migrations) {
            SpecterNode migrationNode = SpecterNode.of(entry.migrationId(),
                    entry.fileName(), NodeType.SCHEMA_MIGRATION)
                    .withMetadata("version", entry.version())
                    .withMetadata("tool", entry.tool())
                    .withMetadata("file", entry.fileName());
            graph.addNode(migrationNode);

            for (String tableName : entry.tables()) {
                String tableNodeId = "schema_table:" + tableName;

                if (graph.getNode(tableNodeId).isEmpty()) {
                    SpecterNode tableNode = SpecterNode.of(tableNodeId,
                            tableName, NodeType.SCHEMA_TABLE)
                            .withMetadata("migrationId", entry.migrationId());
                    graph.addNode(tableNode);
                }

                // Edge: SCHEMA_MIGRATION → SCHEMA_TABLE
                if (graph.getNode(tableNodeId).isPresent()) {
                    graph.addEdge(entry.migrationId(), tableNodeId, EdgeType.CONTAINS);
                }
            }

            for (String colRef : entry.columns()) {
                int dotIdx = colRef.indexOf('.');
                if (dotIdx < 0) continue;
                String tableName = colRef.substring(0, dotIdx);
                String colName = colRef.substring(dotIdx + 1);

                String colNodeId = "schema_column:" + tableName + "." + colName;
                String tableNodeId = "schema_table:" + tableName;

                if (graph.getNode(colNodeId).isEmpty()) {
                    SpecterNode colNode = SpecterNode.of(colNodeId,
                            colName, NodeType.SCHEMA_COLUMN)
                            .withMetadata("table", tableName)
                            .withMetadata("migrationId", entry.migrationId());
                    graph.addNode(colNode);

                    if (graph.getNode(tableNodeId).isPresent()) {
                        graph.addEdge(tableNodeId, colNodeId, EdgeType.CONTAINS);
                    }
                }
            }
        }
    }

    // ── Entity correlation ──────────────────────────────────────────────

    private void correlateEntities(Path sourceRoot) throws IOException {
        // First pass: collect entity → table mappings
        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(this::collectEntityTable);
        }

        // Second pass: create MAPS_TO edges
        for (String tableName : entityTableNames) {
            String schemaTableId = "schema_table:" + tableName.toLowerCase();
            if (graph.getNode(schemaTableId).isEmpty()) continue;

            for (var entry : entityClassToTable.entrySet()) {
                if (entry.getValue().equalsIgnoreCase(tableName)) {
                    String entityNodeId = "class:" + entry.getKey();
                    if (graph.getNode(entityNodeId).isPresent()) {
                        graph.addEdge(entityNodeId, schemaTableId, EdgeType.SCHEMA_MAPS_TO);
                    }
                }
            }
        }

        // Flag entities with no schema
        for (var entry : entityClassToTable.entrySet()) {
            String schemaTableId = "schema_table:" + entry.getValue().toLowerCase();
            if (graph.getNode(schemaTableId).isEmpty()) {
                String entityNodeId = "class:" + entry.getKey();
                graph.getNode(entityNodeId).ifPresent(node ->
                        graph.addNode(node.withMetadata("schemaMissing", "true")));
            }
        }
    }

    private void collectEntityTable(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                boolean isEntity = cls.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("Entity")
                                || a.getNameAsString().equals("Table")
                                || a.getNameAsString().equals("Document"));
                if (!isEntity) return;

                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());

                String tableName = extractTableName(cls);
                if (tableName == null) tableName = className.substring(
                        className.lastIndexOf('.') + 1).toLowerCase();

                entityTableNames.add(tableName);
                entityClassToTable.put(className, tableName);

                // Check column-level correlation
                String schemaTableId = "schema_table:" + tableName.toLowerCase();
                if (graph.getNode(schemaTableId).isPresent()) {
                    Set<String> schemaCols = tableColumns.getOrDefault(
                            tableName.toLowerCase(), Set.of());
                    for (FieldDeclaration field : cls.getFields()) {
                        String colName = extractColumnName(field);
                        if (colName != null && !schemaCols.isEmpty()
                                && !schemaCols.contains(colName)) {
                            String entityNodeId = "class:" + className;
                            graph.getNode(entityNodeId).ifPresent(node ->
                                    graph.addNode(node.withMetadata(
                                            "columnMissing:" + colName, "true")));
                        }
                    }
                }
            });
        } catch (IOException e) {
            log.debug("Failed to collect entity info: {}", file, e);
        }
    }

    private String extractTableName(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("Table")
                .map(ann -> {
                    if (ann instanceof NormalAnnotationExpr na) {
                        return na.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("name"))
                                .findFirst()
                                .map(MemberValuePair::getValue)
                                .filter(v -> v instanceof StringLiteralExpr)
                                .map(v -> ((StringLiteralExpr) v).asString())
                                .orElse(null);
                    }
                    if (ann instanceof SingleMemberAnnotationExpr sma) {
                        if (sma.getMemberValue() instanceof StringLiteralExpr sle) {
                            return sle.asString();
                        }
                    }
                    return null;
                })
                .orElse(null);
    }

    private String extractColumnName(FieldDeclaration field) {
        return field.getAnnotationByName("Column")
                .map(ann -> {
                    if (ann instanceof NormalAnnotationExpr na) {
                        return na.getPairs().stream()
                                .filter(p -> p.getNameAsString().equals("name"))
                                .findFirst()
                                .map(MemberValuePair::getValue)
                                .filter(v -> v instanceof StringLiteralExpr)
                                .map(v -> ((StringLiteralExpr) v).asString())
                                .orElse(null);
                    }
                    return null;
                })
                .orElse(null);
    }

    // ── Inner types ─────────────────────────────────────────────────────

    private record MigrationEntry(
            String version,
            String fileName,
            String tool,
            String migrationId,
            Set<String> tables,
            Set<String> columns
    ) {
        MigrationEntry(String version, String fileName, String tool, String migrationId) {
            this(version, fileName, tool, migrationId, new LinkedHashSet<>(), new LinkedHashSet<>());
        }
    }
}
