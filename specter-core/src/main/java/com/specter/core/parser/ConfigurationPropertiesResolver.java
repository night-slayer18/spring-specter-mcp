package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.specter.core.graph.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects Spring configuration property wiring: {@code @ConfigurationProperties},
 * {@code @Value}, and parses {@code application.properties/YAML} files to build
 * a complete configuration topology in the graph.
 */
@Slf4j
public class ConfigurationPropertiesResolver implements FrameworkResolver {

    private final SpecterGraph graph;
    private final Map<String, String> propertyValues = new LinkedHashMap<>();

    public ConfigurationPropertiesResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Configuration Properties Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        // Parse property files
        parsePropertyFiles(sourceRoot);

        // Scan Java files
        try (var files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(this::resolveFile);
        }

        // Link configuration keys to their values
        for (SpecterNode keyNode : graph.findNodesByType(NodeType.CONFIG_KEY)) {
            String key = keyNode.metadata().get("propertyKey");
            if (key != null && propertyValues.containsKey(key)) {
                String valueNodeId = "config_value:" + key;
                SpecterNode valueNode = SpecterNode.of(valueNodeId,
                        key + "=" + propertyValues.get(key), NodeType.CONFIG_VALUE)
                        .withMetadata("key", key)
                        .withMetadata("value", propertyValues.get(key));
                graph.addNode(valueNode);
                graph.addEdge(keyNode.id(), valueNodeId, EdgeType.RESOLVES_TO);
            }
        }
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());
                parseConfigurationProperties(cls, className);
                parseValueAnnotations(cls, className);
            });
        } catch (IOException e) {
            log.debug("Failed to parse for config: {}", file, e);
        }
    }

    private void parseConfigurationProperties(ClassOrInterfaceDeclaration cls, String className) {
        for (var ann : cls.getAnnotations()) {
            if (!ann.getNameAsString().equals("ConfigurationProperties")) continue;

            String prefix = "";
            if (ann instanceof SingleMemberAnnotationExpr sma) {
                StringLiteralExpr sle = sma.getMemberValue().asStringLiteralExpr();
                prefix = sle.getValue();
            } else if (ann instanceof NormalAnnotationExpr na) {
                MemberValuePair pair = na.getPairs().stream()
                        .filter(p -> "prefix".equals(p.getNameAsString()))
                        .findFirst().orElse(null);
                if (pair != null && pair.getValue().isStringLiteralExpr()) {
                    prefix = pair.getValue().asStringLiteralExpr().getValue();
                }
            }

            String propsNodeId = "config_properties:" + className;
            SpecterNode propsNode = SpecterNode.of(propsNodeId,
                    "ConfigProperties:" + className, NodeType.CONFIG_PROPERTIES)
                    .withMetadata("prefix", prefix)
                    .withMetadata("className", className);
            graph.addNode(propsNode);

            for (FieldDeclaration field : cls.getFields()) {
                for (var variable : field.getVariables()) {
                    String fieldName = variable.getNameAsString();
                    String key = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
                    String keyNodeId = "config_key:" + key;
                    SpecterNode keyNode = SpecterNode.of(keyNodeId, key, NodeType.CONFIG_KEY)
                            .withMetadata("propertyKey", key)
                            .withMetadata("definedIn", className);
                    graph.addNode(keyNode);
                    graph.addEdge(propsNodeId, keyNodeId, EdgeType.CONTAINS);
                }
            }

            graph.getNode("class:" + className).ifPresent(classNode ->
                    graph.addEdge(classNode.id(), propsNodeId, EdgeType.USES_CONFIG));
        }
    }

    private void parseValueAnnotations(ClassOrInterfaceDeclaration cls, String className) {
        cls.findAll(com.github.javaparser.ast.expr.AnnotationExpr.class).forEach(ann -> {
            if (!ann.getNameAsString().equals("Value")) return;

            String expression = "";
            if (ann instanceof SingleMemberAnnotationExpr sma) {
                expression = sma.getMemberValue().asStringLiteralExpr().getValue();
            }

            if (expression.startsWith("${") && expression.contains(":")) {
                // ${key:defaultValue}
                String inner = expression.substring(2, expression.length() - 1);
                int colonIdx = inner.lastIndexOf(':');
                String key = inner.substring(0, colonIdx);
                String keyNodeId = "config_key:" + key;
                SpecterNode keyNode = SpecterNode.of(keyNodeId, key, NodeType.CONFIG_KEY)
                        .withMetadata("propertyKey", key)
                        .withMetadata("definedIn", className)
                        .withMetadata("hasDefault", "true");
                graph.addNode(keyNode);
                graph.getNode("class:" + className).ifPresent(classNode ->
                        graph.addEdge(classNode.id(), keyNodeId, EdgeType.USES_CONFIG));
            } else if (expression.startsWith("${") && expression.endsWith("}")) {
                // ${key} — no default
                String key = expression.substring(2, expression.length() - 1);
                String keyNodeId = "config_key:" + key;
                SpecterNode keyNode = SpecterNode.of(keyNodeId, key, NodeType.CONFIG_KEY)
                        .withMetadata("propertyKey", key)
                        .withMetadata("definedIn", className)
                        .withMetadata("hasDefault", "false");
                graph.addNode(keyNode);
                graph.getNode("class:" + className).ifPresent(classNode ->
                        graph.addEdge(classNode.id(), keyNodeId, EdgeType.USES_CONFIG));
            }
        });
    }

    private void parsePropertyFiles(Path sourceRoot) throws IOException {
        // Walk up from source root to find application.properties/yml files
        Path projectRoot = sourceRoot;
        while (projectRoot != null && !Files.exists(projectRoot.resolve("pom.xml"))
                && !Files.exists(projectRoot.resolve("build.gradle"))) {
            projectRoot = projectRoot.getParent();
            if (projectRoot == null) break;
        }
        if (projectRoot == null) projectRoot = sourceRoot;

        Path resourcesDir = projectRoot.resolve("src/main/resources");
        if (!Files.exists(resourcesDir)) resourcesDir = sourceRoot.resolve("src/main/resources");

        try (var files = Files.list(resourcesDir)) {
            files.filter(f -> f.getFileName().toString().startsWith("application"))
                 .forEach(this::parsePropertyFile);
        } catch (IOException e) {
            log.debug("No properties files found at {}", resourcesDir);
        }
    }

    private void parsePropertyFile(Path file) {
        try {
            String content = Files.readString(file);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;

                if (trimmed.contains("=")) {
                    int eqIdx = trimmed.indexOf('=');
                    String key = trimmed.substring(0, eqIdx).trim();
                    String value = trimmed.substring(eqIdx + 1).trim();
                    propertyValues.put(key, value);
                } else if (trimmed.contains(":")) {
                    int colonIdx = trimmed.indexOf(':');
                    String key = trimmed.substring(0, colonIdx).trim();
                    String value = trimmed.substring(colonIdx + 1).trim();
                    propertyValues.put(key, value);
                }
            }
            log.debug("Parsed {} properties from {}", propertyValues.size(), file.getFileName());
        } catch (IOException e) {
            log.debug("Failed to parse properties file: {}", file, e);
        }
    }
}
