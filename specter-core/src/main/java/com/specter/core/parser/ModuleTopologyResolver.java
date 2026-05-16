package com.specter.core.parser;

import com.specter.core.graph.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves multi-module Maven/Gradle project topology.
 * Discovers submodules, parses inter-module dependencies, and creates
 * {@code MODULE} nodes with {@code DEPENDS_ON} edges.
 *
 * <p>Supports:
 * <ul>
 *   <li>Maven root {@code pom.xml} with {@code <modules>} listing</li>
 *   <li>Gradle {@code settings.gradle}/settings.gradle.kts with include()</li>
 * </ul>
 */
@Slf4j
public class ModuleTopologyResolver {

    private final SpecterGraph graph;
    private final Map<String, ModuleDescriptor> moduleMap = new LinkedHashMap<>();

    public ModuleTopologyResolver(SpecterGraph graph) {
        this.graph = graph;
    }

    /**
     * Discovers all modules from the project root and returns a module map.
     */
    public Map<String, ModuleDescriptor> discover(Path projectRoot) throws IOException {
        Path pom = projectRoot.resolve("pom.xml");
        Path settingsGradle = projectRoot.resolve("settings.gradle");
        Path settingsKts = projectRoot.resolve("settings.gradle.kts");

        if (Files.exists(pom)) {
            discoverMavenModules(projectRoot, pom);
        } else if (Files.exists(settingsGradle) || Files.exists(settingsKts)) {
            discoverGradleModules(projectRoot,
                    Files.exists(settingsKts) ? settingsKts : settingsGradle);
        } else {
            log.warn("No Maven pom.xml or Gradle settings found — treating as single-module");
            String name = projectRoot.getFileName().toString();
            ModuleDescriptor single = new ModuleDescriptor(name, name, "0.1.0",
                    projectRoot, projectRoot, List.of());
            moduleMap.put(name, single);
        }

        // Create MODULE nodes in the graph
        for (var entry : moduleMap.entrySet()) {
            String artifactId = entry.getKey();
            ModuleDescriptor md = entry.getValue();
            String nodeId = "module:" + artifactId;
            SpecterNode moduleNode = SpecterNode.of(nodeId, artifactId, NodeType.MODULE)
                    .withMetadata("groupId", md.groupId())
                    .withMetadata("version", md.version());
            graph.addNode(moduleNode);
        }

        // Create DEPENDS_ON edges
        for (var entry : moduleMap.entrySet()) {
            String sourceId = "module:" + entry.getKey();
            for (String dep : entry.getValue().dependsOn()) {
                graph.getNode("module:" + dep).ifPresent(target ->
                        graph.addEdge(sourceId, target.id(), EdgeType.DEPENDS_ON));
            }
        }

        log.info("Module topology: {} modules, {} inter-module dependencies",
                moduleMap.size(),
                moduleMap.values().stream().mapToInt(m -> m.dependsOn().size()).sum());

        return Collections.unmodifiableMap(moduleMap);
    }

    private void discoverMavenModules(Path projectRoot, Path pomFile) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            NodeList moduleNodes = doc.getElementsByTagName("modules");
            if (moduleNodes.getLength() == 0) {
                String artifactId = extractText(doc, "artifactId");
                moduleMap.put(artifactId, new ModuleDescriptor(
                        artifactId,
                        extractText(doc, "groupId"),
                        extractText(doc, "version"),
                        projectRoot,
                        projectRoot,
                        List.of()
                ));
                return;
            }

            for (int i = 0; i < moduleNodes.getLength(); i++) {
                Node modulesElem = moduleNodes.item(i);
                NodeList children = modulesElem.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "module".equals(child.getNodeName())) {
                        String moduleName = child.getTextContent().trim();
                        Path moduleDir = projectRoot.resolve(moduleName);
                        Path modulePom = moduleDir.resolve("pom.xml");
                        if (Files.exists(modulePom)) {
                            parseMavenModule(moduleDir, modulePom, projectRoot);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Maven pom.xml: {}", pomFile, e);
        }
    }

    private void parseMavenModule(Path moduleDir, Path modulePom, Path projectRoot) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(modulePom.toFile());

            String artifactId = extractText(doc, "artifactId");
            String groupId = extractText(doc, "groupId");
            String version = extractText(doc, "version");
            if (version == null || version.isEmpty()) {
                version = "0.1.0";
            }

            List<String> deps = new ArrayList<>();
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element depElement = (Element) depNodes.item(i);
                NodeList childNodes = depElement.getChildNodes();
                for (int j = 0; j < childNodes.getLength(); j++) {
                    Node child = childNodes.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "artifactId".equals(child.getNodeName())) {
                        deps.add(child.getTextContent().trim());
                    }
                }
            }

            Path sourceRoot = moduleDir;
            Path srcMainJava = moduleDir.resolve("src/main/java");
            if (Files.exists(srcMainJava)) {
                sourceRoot = srcMainJava;
            }

            moduleMap.put(artifactId, new ModuleDescriptor(
                    artifactId, groupId != null ? groupId : artifactId,
                    version, sourceRoot, moduleDir, deps));
        } catch (Exception e) {
            log.warn("Failed to parse module pom: {}", modulePom, e);
        }
    }

    private void discoverGradleModules(Path projectRoot, Path settingsFile) throws IOException {
        try {
            String content = Files.readString(settingsFile);
            List<String> modules = new ArrayList<>();
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("include")) {
                    String rest = trimmed.substring("include".length()).trim();
                    rest = rest.replaceAll("[()'\"]", "").trim();
                    if (rest.startsWith(":")) rest = rest.substring(1);
                    String[] parts = rest.split(",");
                    for (String part : parts) {
                        String name = part.trim().replace(":", "/");
                        modules.add(name);
                    }
                }
            }

            for (String moduleName : modules) {
                Path moduleDir = projectRoot.resolve(moduleName);
                Path sourceRoot = moduleDir.resolve("src/main/java");
                if (!Files.exists(sourceRoot)) sourceRoot = moduleDir;
                moduleMap.put(moduleName, new ModuleDescriptor(
                        moduleName, moduleName, "0.1.0",
                        sourceRoot, moduleDir, List.of()));
            }
        } catch (IOException e) {
            log.warn("Failed to parse Gradle settings: {}", settingsFile, e);
        }
    }

    private String extractText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        NodeList allElements = doc.getDocumentElement().getElementsByTagName(tagName);
        if (allElements.getLength() > 0) {
            return allElements.item(0).getTextContent().trim();
        }
        return null;
    }

    public Map<String, ModuleDescriptor> getModuleMap() {
        return Collections.unmodifiableMap(moduleMap);
    }

    /**
     * Describes a single Maven/Gradle module in the multi-module project.
     */
    public record ModuleDescriptor(
            String artifactId,
            String groupId,
            String version,
            Path sourceRoot,
            Path classesRoot,
            List<String> dependsOn
    ) {}
}
