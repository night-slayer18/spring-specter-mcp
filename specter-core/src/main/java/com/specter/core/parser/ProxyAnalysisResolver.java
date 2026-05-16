package com.specter.core.parser;

import com.specter.core.graph.EdgeType;
import com.specter.core.graph.NodeType;
import com.specter.core.graph.SpecterGraph;
import com.specter.core.graph.SpecterNode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Secondary analysis phase that inspects compiled {@code .class} files via ASM
 * to detect Spring CGLIB proxies, JDK dynamic proxies, and AOP interception
 * chains that are invisible to source-level AST parsing.
 *
 * <p>Augments the graph with proxy→target {@code CALLS} edges so that blast
 * radius and dependency cycle analysis can trace through runtime interception
 * to the concrete implementation.
 */
@Slf4j
public class ProxyAnalysisResolver implements FrameworkResolver {

    private static final String CGLIB_MARKER = "$$";
    private static final String SPRING_CGLIB = "CGLIB";
    private static final String JDK_PROXY_MARKER = "$Proxy";
    private static final String CGLIB_FACTORY = "org/springframework/cglib/proxy/Factory";

    private final SpecterGraph graph;
    private final Path classesRoot;

    public ProxyAnalysisResolver(SpecterGraph graph, Path classesRoot) {
        this.graph = graph;
        this.classesRoot = classesRoot;
    }

    @Override
    public String name() {
        return "Bytecode Proxy Analysis";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(classesRoot)) {
            log.info("Bytecode root {} not found — skipping proxy analysis", classesRoot);
            return;
        }

        int proxyCount = 0;
        try (Stream<Path> classFiles = Files.walk(classesRoot)) {
            var files = classFiles
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();

            for (Path classFile : files) {
                if (analyzeClassFile(classFile)) {
                    proxyCount++;
                }
            }
        }

        log.info("Proxy analysis complete — {} proxy classes detected", proxyCount);
    }

    private boolean analyzeClassFile(Path classFile) {
        try (InputStream in = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(in);
            ProxyClassVisitor visitor = new ProxyClassVisitor();
            reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (visitor.proxyType == null) return false;

            String proxyClassName = visitor.className.replace('/', '.');
            String targetClassName = visitor.targetClassName();

            if (targetClassName == null) return false;

            String proxyNodeId = "proxy:" + proxyClassName;
            String targetNodeId = "class:" + targetClassName;

            SpecterNode proxyNode = SpecterNode.of(proxyNodeId, proxyClassName, NodeType.PROXY)
                    .withMetadata("proxyType", visitor.proxyType)
                    .withMetadata("targetClass", targetClassName)
                    .withMetadata("classFile", classFile.toString());
            graph.addNode(proxyNode);

            graph.getNode(targetNodeId).ifPresentOrElse(
                    target -> {},
                    () -> graph.addNode(SpecterNode.of(targetNodeId, targetClassName, NodeType.SERVICE))
            );

            graph.addEdge(proxyNodeId, targetNodeId, EdgeType.CALLS);

            return true;
        } catch (IOException e) {
            log.debug("Failed to analyze class file: {}", classFile, e);
            return false;
        }
    }

    // ── ASM visitor ────────────────────────────────────────────────────────

    private static class ProxyClassVisitor extends ClassVisitor {

        String className;
        String superName;
        String[] interfaces;
        String proxyType; // "cglib" or "jdk"

        ProxyClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName, String[] interfaces) {
            this.className = name;
            this.superName = superName;
            this.interfaces = interfaces;

            if (name.contains(CGLIB_MARKER) && name.contains(SPRING_CGLIB)) {
                proxyType = "cglib";
            } else if (name.contains(JDK_PROXY_MARKER)) {
                proxyType = "jdk";
            }

            if (proxyType == null && interfaces != null) {
                for (String iface : interfaces) {
                    if (CGLIB_FACTORY.equals(iface)) {
                        proxyType = "cglib";
                        break;
                    }
                }
            }
        }

        String targetClassName() {
            if ("cglib".equals(proxyType) && superName != null
                    && !"java/lang/Object".equals(superName)) {
                return superName.replace('/', '.');
            }
            if ("jdk".equals(proxyType) && interfaces != null && interfaces.length > 0) {
                for (String iface : interfaces) {
                    if (!iface.startsWith("java/") && !iface.startsWith("jdk/")) {
                        return iface.replace('/', '.');
                    }
                }
                if (interfaces.length > 0) {
                    return interfaces[0].replace('/', '.');
                }
            }
            return null;
        }
    }
}
