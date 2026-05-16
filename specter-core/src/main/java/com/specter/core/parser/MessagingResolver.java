package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract messaging topology resolver. Detects producers and consumers across
 * Kafka, RabbitMQ, JMS, and Spring Cloud Stream — storing the underlying
 * technology as a key-value trait on each node instead of hardcoding
 * technology-specific stereotypes.
 *
 * <p>Produces {@link NodeType#MESSAGE_PRODUCER}, {@link NodeType#MESSAGE_CONSUMER},
 * and {@link NodeType#MESSAGE_TOPIC} nodes.
 */
@Slf4j
public class MessagingResolver implements FrameworkResolver {

    private static final Set<String> LISTENER_ANNOTATIONS = Set.of(
            "KafkaListener", "RabbitListener", "JmsListener", "StreamListener"
    );

    private static final String KAFKA_TEMPLATE = "KafkaTemplate";
    private static final String RABBIT_TEMPLATE = "RabbitTemplate";
    private static final String JMS_TEMPLATE = "JmsTemplate";
    private static final String STREAM_BRIDGE = "StreamBridge";

    private final SpecterGraph graph;

    public MessagingResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Messaging & Event Streaming";
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
            cu.findAll(ClassOrInterfaceDeclaration.class)
                .forEach(cls -> resolveClass(cls, file));
        } catch (IOException e) {
            log.warn("Failed to parse file for messaging resolution: {}", file, e);
        }
    }

    private void resolveClass(ClassOrInterfaceDeclaration cls, Path file) {
        String className = cls.getFullyQualifiedName().orElse(cls.getNameAsString());

        // ── Message listeners (consumers) ─────────────────────────────────
        for (MethodDeclaration method : cls.getMethods()) {
            for (AnnotationExpr ann : method.getAnnotations()) {
                String annName = ann.getNameAsString();
                if (!LISTENER_ANNOTATIONS.contains(annName)) continue;

                String technology = listenerTechnology(annName);
                String channelName = extractListenerChannel(ann, annName);
                if (channelName == null) continue;

                String consumerNodeId = "class:" + className;
                String topicNodeId = "message_topic:" + channelName;

                SpecterNode consumerNode = SpecterNode.of(consumerNodeId, className, NodeType.MESSAGE_CONSUMER)
                        .withMetadata("sourceFile", file.toString())
                        .withMetadata("method", method.getNameAsString())
                        .withMetadata("channel", channelName)
                        .withMetadata("technology", technology);
                graph.addNode(consumerNode);

                SpecterNode topicNode = SpecterNode.of(topicNodeId, channelName, NodeType.MESSAGE_TOPIC)
                        .withMetadata("technology", technology)
                        .withMetadata("consumers", className);
                graph.addNode(topicNode);

                graph.addEdge(topicNodeId, consumerNodeId, EdgeType.SUBSCRIBES_FROM);
            }
        }

        // ── Template-based producers ──────────────────────────────────────
        for (MethodDeclaration method : cls.getMethods()) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                String scopeStr = call.getScope().map(Object::toString).orElse("");
                String channel = extractProducerChannel(call, scopeStr);
                String technology = producerTechnology(scopeStr);
                if (channel == null || technology == null) continue;

                String producerNodeId = "class:" + className;
                String topicNodeId = "message_topic:" + channel;

                SpecterNode producerNode = SpecterNode.of(producerNodeId, className, NodeType.MESSAGE_PRODUCER)
                        .withMetadata("sourceFile", file.toString())
                        .withMetadata("method", method.getNameAsString())
                        .withMetadata("channel", channel)
                        .withMetadata("technology", technology);
                graph.addNode(producerNode);

                SpecterNode topicNode = SpecterNode.of(topicNodeId, channel, NodeType.MESSAGE_TOPIC)
                        .withMetadata("technology", technology)
                        .withMetadata("producers", className);
                graph.addNode(topicNode);

                graph.addEdge(producerNodeId, topicNodeId, EdgeType.PUBLISHES_TO);
            }
        }

        // ── Functional Spring Cloud Stream beans ──────────────────────────
        detectFunctionalStreamBeans(cls, className, file);
    }

    // ── Listener helpers ───────────────────────────────────────────────────

    private String listenerTechnology(String annotationName) {
        return switch (annotationName) {
            case "KafkaListener" -> "kafka";
            case "RabbitListener" -> "rabbitmq";
            case "JmsListener" -> "jms";
            case "StreamListener" -> "spring-cloud-stream";
            default -> "unknown";
        };
    }

    private String extractListenerChannel(AnnotationExpr annotation, String annotationName) {
        if (annotation.isNormalAnnotationExpr()) {
            var normalAnn = annotation.asNormalAnnotationExpr();
            Optional<String> fromTopics = normalAnn.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("topics"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replaceAll("[{}\"\\s]", ""));
            if (fromTopics.isPresent()) return fromTopics.get();

            Optional<String> fromQueues = normalAnn.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("queues") || p.getNameAsString().equals("destination"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replaceAll("[{}\"\\s]", ""));
            if (fromQueues.isPresent()) return fromQueues.get();

            Optional<String> fromValue = normalAnn.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("bindings"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replaceAll("[{}\"\\s]", ""));
            return fromValue.orElse(null);
        }
        if (annotation.isSingleMemberAnnotationExpr()) {
            return annotation.asSingleMemberAnnotationExpr()
                    .getMemberValue().toString().replaceAll("\"", "");
        }
        return null;
    }

    // ── Producer helpers ───────────────────────────────────────────────────

    private String producerTechnology(String scopeStr) {
        if (scopeStr.contains(KAFKA_TEMPLATE)) return "kafka";
        if (scopeStr.contains(RABBIT_TEMPLATE)) return "rabbitmq";
        if (scopeStr.contains(JMS_TEMPLATE)) return "jms";
        if (scopeStr.contains(STREAM_BRIDGE)) return "spring-cloud-stream";
        return null;
    }

    private String extractProducerChannel(MethodCallExpr call, String scopeStr) {
        String methodName = call.getNameAsString();

        boolean isSend = "send".equals(methodName);
        boolean isConvertAndSend = "convertAndSend".equals(methodName) || "convertAndSend".equals(methodName);

        if (!isSend && !isConvertAndSend) return null;

        if (call.getArguments().isEmpty()) return null;
        var firstArg = call.getArgument(0);
        if (firstArg instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) firstArg).getValue();
        }
        return null;
    }

    // ── Functional Stream bean detection ───────────────────────────────────

    private void detectFunctionalStreamBeans(ClassOrInterfaceDeclaration cls, String className, Path file) {
        boolean isConfig = cls.getAnnotations().stream()
                .anyMatch(a -> "Configuration".equals(a.getNameAsString()));

        if (!isConfig) return;

        for (MethodDeclaration method : cls.getMethods()) {
            String returnType = method.getType().asString();
            String beanName = method.getAnnotationByName("Bean")
                    .map(a -> extractBeanName(a, method))
                    .orElse(method.getNameAsString());

            if (returnType.startsWith("Function<") || returnType.startsWith("java.util.function.Function<")) {
                addStreamFunctionNode(className, beanName, "function", file);
            } else if (returnType.startsWith("Consumer<") || returnType.startsWith("java.util.function.Consumer<")) {
                String consumerNodeId = "class:" + className;
                SpecterNode consumerNode = SpecterNode.of(consumerNodeId, className, NodeType.MESSAGE_CONSUMER)
                        .withMetadata("sourceFile", file.toString())
                        .withMetadata("method", method.getNameAsString())
                        .withMetadata("beanName", beanName)
                        .withMetadata("technology", "spring-cloud-stream");
                graph.addNode(consumerNode);
            } else if (returnType.startsWith("Supplier<") || returnType.startsWith("java.util.function.Supplier<")) {
                String producerNodeId = "class:" + className;
                SpecterNode producerNode = SpecterNode.of(producerNodeId, className, NodeType.MESSAGE_PRODUCER)
                        .withMetadata("sourceFile", file.toString())
                        .withMetadata("method", method.getNameAsString())
                        .withMetadata("beanName", beanName)
                        .withMetadata("technology", "spring-cloud-stream");
                graph.addNode(producerNode);
            }
        }
    }

    private void addStreamFunctionNode(String className, String beanName, String role, Path file) {
        String nodeId = "class:" + className + "#" + beanName;
        SpecterNode fnNode = SpecterNode.of(nodeId, beanName, NodeType.MESSAGE_CONSUMER)
                .withMetadata("sourceFile", file.toString())
                .withMetadata("beanName", beanName)
                .withMetadata("technology", "spring-cloud-stream")
                .withMetadata("role", role);
        graph.addNode(fnNode);
    }

    private String extractBeanName(AnnotationExpr beanAnn, MethodDeclaration method) {
        if (beanAnn.isNormalAnnotationExpr()) {
            return beanAnn.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("name"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replaceAll("\"", ""))
                    .orElse(method.getNameAsString());
        }
        if (beanAnn.isSingleMemberAnnotationExpr()) {
            String val = beanAnn.asSingleMemberAnnotationExpr().getMemberValue().toString();
            return val.replaceAll("\"", "");
        }
        return method.getNameAsString();
    }
}
