package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
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
 * Pass 2 observability architecture resolver. Scans source code for
 * Micrometer metrics, Spring Boot Actuator health indicators, distributed
 * tracing annotations, and structured logging markers.
 *
 * <p>Populates the graph with METRIC, HEALTH_INDICATOR, and TRACE_SPAN
 * nodes, connected via MEASURES and TRACES edges, enabling the
 * {@link com.specter.core.analysis.ArchitecturalHealthAnalyzer} to
 * compute a real observability health score.
 */
@Slf4j
public class ObservabilityResolver implements FrameworkResolver {

    private static final Set<String> LOGGING_ANNOTATIONS = Set.of("Slf4j", "Log4j2", "CommonsLog");
    private static final Set<String> TRACING_ANNOTATIONS = Set.of("NewSpan", "WithSpan");

    private final SpecterGraph graph;

    public ObservabilityResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Observability Architecture Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(this::resolveFile);
        }
        log.info("Observability resolution complete");
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());
                String nodeId = "class:" + className;

                resolveLogging(cls, nodeId);
                resolveMetrics(cls, className, nodeId);
                resolveMetricBuilders(cls, className, nodeId);
                resolveHealthIndicators(cls, className, nodeId);
                resolveTracing(cls, className, nodeId);
            });
        } catch (IOException e) {
            log.debug("Failed to parse for observability: {}", file, e);
        }
    }

    // ── Structured Logging ──────────────────────────────────────────────

    private void resolveLogging(ClassOrInterfaceDeclaration cls, String nodeId) {
        for (AnnotationExpr ann : cls.getAnnotations()) {
            if (LOGGING_ANNOTATIONS.contains(ann.getNameAsString())) {
                graph.getNode(nodeId).ifPresent(node -> {
                    SpecterNode enriched = node.withMetadata("hasStructuredLogging", "true");
                    graph.addNode(enriched);
                });
                break;
            }
        }
    }

    // ── Micrometer @Timed ───────────────────────────────────────────────

    private void resolveMetrics(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        boolean hasMeterRegistry = hasMeterRegistryInjection(cls);
        boolean hasTimed = false;

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                if (!ann.getNameAsString().equals("Timed")) continue;
                hasTimed = true;

                String metricName = className + "." + method.getNameAsString();
                Map<String, String> metricMeta = new LinkedHashMap<>();

                if (ann instanceof SingleMemberAnnotationExpr sma) {
                    if (sma.getMemberValue().isStringLiteralExpr()) {
                        metricName = sma.getMemberValue().asStringLiteralExpr().getValue();
                    }
                } else if (ann instanceof NormalAnnotationExpr na) {
                    for (MemberValuePair pair : na.getPairs()) {
                        String pn = pair.getNameAsString();
                        if ("value".equals(pn) && pair.getValue().isStringLiteralExpr()) {
                            metricName = pair.getValue().asStringLiteralExpr().getValue();
                        } else if ("percentiles".equals(pn)) {
                            metricMeta.put("percentiles", pair.getValue().toString());
                        } else if ("description".equals(pn) && pair.getValue().isStringLiteralExpr()) {
                            metricMeta.put("description", pair.getValue().asStringLiteralExpr().getValue());
                        }
                    }
                }

                String metricNodeId = "metric:" + metricName;
                SpecterNode metricNode = SpecterNode.of(metricNodeId, metricName, NodeType.METRIC);
                for (var entry : metricMeta.entrySet()) {
                    metricNode = metricNode.withMetadata(entry.getKey(), entry.getValue());
                }
                metricNode = metricNode.withMetadata("measuredBy", className)
                        .withMetadata("sourceMethod", method.getNameAsString());
                graph.addNode(metricNode);
                graph.addEdge(nodeId, metricNodeId, EdgeType.MEASURES);
            }
        }

        if (hasMeterRegistry && !hasTimed) {
            graph.getNode(nodeId).ifPresent(node -> {
                graph.addNode(node.withMetadata("hasMeterRegistry", "true"));
            });
        }

        if (hasMeterRegistry || hasTimed) {
            graph.getNode(nodeId).ifPresent(node -> {
                graph.addNode(node.withMetadata("instrumented", "true"));
            });
        }
    }

    private boolean hasMeterRegistryInjection(ClassOrInterfaceDeclaration cls) {
        for (FieldDeclaration field : cls.getFields()) {
            for (var variable : field.getVariables()) {
                String typeName = variable.getTypeAsString();
                if (typeName.equals("MeterRegistry") || typeName.endsWith(".MeterRegistry")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Counter.builder / Timer.builder ──────────────────────────────────

    private void resolveMetricBuilders(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        cls.findAll(MethodCallExpr.class).forEach(mce -> {
            String expr = mce.getNameAsString();
            if (!expr.equals("register")) return;
            if (mce.getScope().isEmpty()) return;

            String scopeStr = mce.getScope().get().toString();
            String builderMatch = detectMetricBuilder(scopeStr);
            if (builderMatch == null) return;

            String metricNodeId = "metric:" + builderMatch;
            SpecterNode metricNode = SpecterNode.of(metricNodeId, builderMatch, NodeType.METRIC)
                    .withMetadata("detectedVia", "MeterRegistry.builder")
                    .withMetadata("measuredBy", className);
            graph.addNode(metricNode);
            graph.addEdge(nodeId, metricNodeId, EdgeType.MEASURES);
        });
    }

    private String detectMetricBuilder(String scopeExpr) {
        String[] prefixes = {"Counter.builder(", "Timer.builder(", "Gauge.builder(",
                             "DistributionSummary.builder(", "LongTaskTimer.builder("};
        for (String prefix : prefixes) {
            if (!scopeExpr.startsWith(prefix)) continue;
            String args = scopeExpr.substring(prefix.length());
            if (args.startsWith("\"")) {
                int endQuote = args.indexOf('"', 1);
                if (endQuote > 0) return args.substring(1, endQuote);
            }
            break;
        }
        return null;
    }

    // ── Health Indicators ───────────────────────────────────────────────

    private void resolveHealthIndicators(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        for (var implemented : cls.getImplementedTypes()) {
            String ifaceName = implemented.getNameAsString();
            if (!ifaceName.equals("HealthIndicator") && !ifaceName.endsWith(".HealthIndicator")) {
                continue;
            }

            String healthNodeId = "health_indicator:" + className;
            SpecterNode healthNode = SpecterNode.of(healthNodeId,
                    "HealthIndicator:" + className, NodeType.HEALTH_INDICATOR)
                    .withMetadata("implementedBy", className);

            graph.addNode(healthNode);
            graph.addEdge(nodeId, healthNodeId, EdgeType.IMPLEMENTS);

            // Check for @ConditionalOnEnabledHealthIndicator
            for (AnnotationExpr ann : cls.getAnnotations()) {
                if (ann.getNameAsString().equals("ConditionalOnEnabledHealthIndicator")) {
                    graph.addNode(healthNode.withMetadata("conditional", "true"));
                    break;
                }
            }
            break;
        }
    }

    // ── Distributed Tracing ─────────────────────────────────────────────

    private void resolveTracing(ClassOrInterfaceDeclaration cls, String className, String nodeId) {
        boolean hasTracer = false;

        for (FieldDeclaration field : cls.getFields()) {
            for (var variable : field.getVariables()) {
                String typeName = variable.getTypeAsString();
                if (typeName.equals("Tracer") || typeName.endsWith(".Tracer")) {
                    hasTracer = true;
                    break;
                }
            }
            if (hasTracer) break;
        }

        if (hasTracer) {
            graph.getNode(nodeId).ifPresent(node -> {
                graph.addNode(node.withMetadata("hasTracerInjection", "true"));
                graph.addNode(node.withMetadata("instrumented", "true"));
            });
        }

        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                String annName = ann.getNameAsString();
                if (!TRACING_ANNOTATIONS.contains(annName)) continue;

                String spanName = className + "." + method.getNameAsString();
                if (ann instanceof SingleMemberAnnotationExpr sma
                        && sma.getMemberValue().isStringLiteralExpr()) {
                    spanName = sma.getMemberValue().asStringLiteralExpr().getValue();
                } else if (ann instanceof NormalAnnotationExpr na) {
                    for (MemberValuePair pair : na.getPairs()) {
                        if ("value".equals(pair.getNameAsString())
                                && pair.getValue().isStringLiteralExpr()) {
                            spanName = pair.getValue().asStringLiteralExpr().getValue();
                        }
                    }
                }

                String spanNodeId = "trace_span:" + spanName;
                SpecterNode spanNode = SpecterNode.of(spanNodeId, spanName, NodeType.TRACE_SPAN)
                        .withMetadata("tracedBy", className)
                        .withMetadata("sourceMethod", method.getNameAsString());
                graph.addNode(spanNode);
                graph.addEdge(nodeId, spanNodeId, EdgeType.TRACES);

                graph.getNode(nodeId).ifPresent(node -> {
                    graph.addNode(node.withMetadata("instrumented", "true"));
                });
                break;
            }
        }
    }
}
