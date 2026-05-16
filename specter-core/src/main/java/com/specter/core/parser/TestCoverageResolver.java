package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.*;
import com.specter.core.graph.*;

import java.io.IOException;
import java.nio.file.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Maps test classes to production components, distinguishing actual coverage
 * ({@code TESTS} edges) from mock-only references ({@code MOCKS} edges).
 * Detects Spring Boot test slice annotations, Mockito mocks, and injection targets.
 */
@Slf4j
public class TestCoverageResolver implements FrameworkResolver {

    private final SpecterGraph graph;

    public TestCoverageResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "Test Coverage Resolver";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        try (var files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> p.toString().contains("Test") || p.toString().contains("test"))
                 .forEach(this::resolveFile);
        }
        log.info("Test coverage analysis complete");
    }

    private void resolveFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = cls.getFullyQualifiedName()
                        .orElse(cls.getNameAsString());

                String testType = detectTestType(cls);
                String testId = "test:" + className;
                SpecterNode testNode = SpecterNode.of(testId,
                        className, NodeType.TEST)
                        .withMetadata("testType", testType);
                graph.addNode(testNode);

                // Detect @WebMvcTest(SomeController.class), @DataJpaTest, etc.
                for (var ann : cls.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    if (annName.contains("Test") && ann instanceof SingleMemberAnnotationExpr sma) {
                        String targetClass = sma.getMemberValue().toString()
                                .replace(".class", "").trim();
                        addTestedClass(testNode, targetClass);
                    } else if (annName.contains("Test") && ann instanceof NormalAnnotationExpr na) {
                        for (var pair : na.getPairs()) {
                            String val = pair.getValue().toString().replace(".class", "").trim();
                            addTestedClass(testNode, val);
                        }
                    }
                }

                // Detect @InjectMocks / @Mock / @MockBean
                cls.getFields().forEach(field -> {
                    for (var ann : field.getAnnotations()) {
                        String annName = ann.getNameAsString();
                        if ("InjectMocks".equals(annName)) {
                            String type = field.getElementType().asString();
                            addTestedClass(testNode, type);
                        }
                        if ("Mock".equals(annName) || "MockBean".equals(annName)) {
                            String type = field.getElementType().asString();
                            addMockedClass(testNode, type);
                        }
                    }
                });
            });
        } catch (IOException e) {
            log.debug("Failed to parse test file: {}", file, e);
        }
    }

    private String detectTestType(ClassOrInterfaceDeclaration cls) {
        for (var ann : cls.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("SpringBootTest".equals(name)) return "INTEGRATION";
            if ("WebMvcTest".equals(name) || "DataJpaTest".equals(name)
                    || "JsonTest".equals(name) || "RestClientTest".equals(name)) return "SLICE";
        }
        for (var ann : cls.getAnnotations()) {
            if ("ExtendWith".equals(ann.getNameAsString())) return "UNIT";
        }
        return "UNIT";
    }

    private void addTestedClass(SpecterNode testNode, String className) {
        String classId = resolveClassId(className);
        graph.getNode(classId).ifPresent(target ->
                graph.addEdge(testNode.id(), target.id(), EdgeType.TESTS));
    }

    private void addMockedClass(SpecterNode testNode, String className) {
        String classId = resolveClassId(className);
        graph.getNode(classId).ifPresent(target ->
                graph.addEdge(testNode.id(), target.id(), EdgeType.MOCKS));
    }

    private String resolveClassId(String className) {
        String simple = className.contains(".") ?
                className.substring(className.lastIndexOf('.') + 1) : className;
        // Try FQN first, then simple name, then class: prefix
        return graph.getNode("class:" + className).isPresent() ? "class:" + className
                : graph.findNodeByName(simple).map(SpecterNode::id).orElse("class:" + className);
    }
}
