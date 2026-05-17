package com.specter.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.specter.core.graph.NodeType;
import com.specter.core.registry.BeanRegistry;
import com.specter.core.registry.BeanRegistry.BeanMetadata;
import com.specter.core.registry.BeanRegistry.ConditionalMetadata;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Pass 1 of the Runtime Context Simulator. Simulates Spring's
 * {@code @ComponentScan} behaviour to determine which beans are genuinely
 * active in the current context.
 *
 * <p>Execution order:
 * <ol>
 *   <li>Locate {@code @SpringBootApplication} or {@code @Configuration} classes</li>
 *   <li>Extract {@code @ComponentScan} base packages (default: package of annotated class)</li>
 *   <li>Walk only source files within the resolved scan path</li>
 *   <li>Apply {@code @ComponentScan.Filter} exclude/include rules</li>
 *   <li>Check {@code @Profile} and {@code @ConditionalOn*} annotations</li>
 *   <li>Populate the shared {@link BeanRegistry} with metadata</li>
 * </ol>
 */
@Slf4j
public class BeanRegistryResolver implements FrameworkResolver {

    private static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            "RestController", "Controller", "Service", "Repository", "Component", "Configuration"
    );

    private static final Set<String> CONDITIONAL_ANNOTATIONS = Set.of(
            "ConditionalOnProperty", "ConditionalOnClass",
            "ConditionalOnMissingBean", "ConditionalOnBean", "ConditionalOnExpression"
    );

    private final BeanRegistry registry;
    private final Set<String> activeProfiles;

    public BeanRegistryResolver(BeanRegistry registry, Set<String> activeProfiles) {
        this.registry = registry;
        this.activeProfiles = Set.copyOf(activeProfiles);
    }

    @Override
    public String name() {
        return "Component Scan Simulation";
    }

    @Override
    public void resolve(Path sourceRoot) throws IOException {
        // Load application properties for @ConditionalOnProperty evaluation
        Map<String, String> appProperties = loadApplicationProperties(sourceRoot);
        log.debug("Loaded {} application properties for conditional evaluation", appProperties.size());

        Set<String> scanPackages  = new LinkedHashSet<>();
        Set<String> excludeFilters = new LinkedHashSet<>();
        Set<String> includeFilters = new LinkedHashSet<>();

        // Step 1: Discover root config classes
        List<Path> javaFiles = collectJavaFiles(sourceRoot);
        for (Path file : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                List<ClassOrInterfaceDeclaration> types = cu.findAll(ClassOrInterfaceDeclaration.class);

                for (ClassOrInterfaceDeclaration cls : types) {
                    if (isBootOrConfig(cls)) {
                        String className = cls.getFullyQualifiedName()
                                .orElse(cls.getNameAsString());
                        String classPackage = getPackageName(cu);
                        scanPackages.add(classPackage);

                        extractComponentScan(cls, scanPackages, excludeFilters, includeFilters);

                        registerConfigBeans(cls, className, file);
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to parse during scan discovery: {}", file, e);
            }
        }

        if (scanPackages.isEmpty()) {
            log.warn("No @SpringBootApplication or @Configuration found — falling back to full scan");
            scanPackages.add(""); // will match everything
        }

        log.info("Component scan packages: {}", scanPackages);
        log.info("Active profiles: {}", activeProfiles);

        // Step 2: Scan only within resolved packages
        for (Path file : javaFiles) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                String filePackage = getPackageName(cu);

                if (!isInScanPath(filePackage, scanPackages)) continue;

                List<ClassOrInterfaceDeclaration> types = cu.findAll(ClassOrInterfaceDeclaration.class);
                for (ClassOrInterfaceDeclaration cls : types) {
                    String className = cls.getFullyQualifiedName()
                            .orElse(cls.getNameAsString());

                    if (isExcluded(className, cls, excludeFilters, includeFilters)) continue;

                    if (!isStereotypedOrBean(cls)) continue;

                    registerStereotypeBean(cls, className, filePackage, file, appProperties);
                }
            } catch (IOException e) {
                log.debug("Failed to parse during scan: {}", file, e);
            }
        }

        log.info("Bean registry built: {} active beans", registry.size());
    }

    // ── Root discovery ───────────────────────────────────────────────────

    private boolean isBootOrConfig(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("SpringBootApplication").isPresent()
                || cls.getAnnotationByName("Configuration").isPresent();
    }

    private void extractComponentScan(ClassOrInterfaceDeclaration cls,
                                       Set<String> scanPackages,
                                       Set<String> excludeFilters,
                                       Set<String> includeFilters) {
        cls.getAnnotationByName("ComponentScan").ifPresent(scanAnn -> {
            if (scanAnn.isNormalAnnotationExpr()) {
                var normal = scanAnn.asNormalAnnotationExpr();

                normal.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("basePackages"))
                        .findFirst()
                        .ifPresent(p -> {
                            String val = p.getValue().toString()
                                    .replaceAll("[{}\"\\s]", "");
                            if (!val.isEmpty()) {
                                for (String pkg : val.split(",")) {
                                    scanPackages.add(pkg.trim());
                                }
                            }
                        });

                normal.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("basePackageClasses"))
                        .findFirst()
                        .ifPresent(p -> {
                            String val = p.getValue().toString()
                                    .replaceAll("[{}\\s]", "");
                            for (String clsRef : val.split(",")) {
                                String pkg = clsRef.contains(".class")
                                        ? clsRef.replace(".class", "").trim()
                                        : clsRef.trim();
                                int lastDot = pkg.lastIndexOf('.');
                                if (lastDot > 0) scanPackages.add(pkg.substring(0, lastDot));
                            }
                        });

                extractFilters(normal, excludeFilters, includeFilters);
            }
        });
    }

    private void extractFilters(NormalAnnotationExpr scanAnn,
                                 Set<String> excludeFilters,
                                 Set<String> includeFilters) {
        scanAnn.getPairs().stream()
                .filter(p -> p.getNameAsString().equals("excludeFilters")
                        || p.getNameAsString().equals("includeFilters"))
                .forEach(p -> {
                    boolean isExclude = p.getNameAsString().equals("excludeFilters");
                    String val = p.getValue().toString();

                    // Extract class references from @Filter annotations
                    Pattern classPattern = Pattern.compile("classes\\s*=\\s*\\{?([^}]+)}?");
                    var matcher = classPattern.matcher(val);
                    while (matcher.find()) {
                        String classes = matcher.group(1)
                                .replaceAll("\\.class", "")
                                .replaceAll("\\s+", "");
                        for (String c : classes.split(",")) {
                            if (isExclude) excludeFilters.add(c.trim());
                            else includeFilters.add(c.trim());
                        }
                    }
                });
    }

    // ── Stereotype registration ─────────────────────────────────────────

    private void registerStereotypeBean(ClassOrInterfaceDeclaration cls,
                                         String className, String classPackage,
                                         Path file, Map<String, String> appProperties) {
        String beanName = resolveBeanName(cls);
        NodeType stereotype = classifyStereotype(cls);

        BeanMetadata.Builder builder = BeanMetadata.builder(className, beanName, stereotype);

        // Collect implemented interfaces
        for (ClassOrInterfaceType iface : cls.getImplementedTypes()) {
            builder.addInterface(iface.getNameWithScope());
        }

        // @Primary
        cls.getAnnotationByName("Primary")
                .ifPresent(a -> builder.primary(true));

        // @Qualifier
        cls.getAnnotationByName("Qualifier").ifPresent(q -> {
            if (q.isSingleMemberAnnotationExpr()) {
                builder.qualifier(q.asSingleMemberAnnotationExpr()
                        .getMemberValue().toString().replaceAll("\"", ""));
            }
        });

        // @Scope / @SessionScope / @RequestScope
        cls.getAnnotationByName("Scope").ifPresent(s -> {
            if (s.isSingleMemberAnnotationExpr()) {
                builder.scope(s.asSingleMemberAnnotationExpr()
                        .getMemberValue().toString().replaceAll("\"", ""));
            }
        });
        if (cls.getAnnotationByName("SessionScope").isPresent()) builder.scope("session");
        if (cls.getAnnotationByName("RequestScope").isPresent()) builder.scope("request");
        if (cls.getAnnotationByName("PrototypeScope").isPresent()) builder.scope("prototype");

        // @Profile
        cls.getAnnotationByName("Profile").ifPresent(prof -> {
            Set<String> profiles = extractStringValues(prof);
            for (String p : profiles) {
                builder.addProfile(p);
            }
        });

        // @ConditionalOn*
        for (AnnotationExpr ann : cls.getAnnotations()) {
            String annName = ann.getNameAsString();
            if (!CONDITIONAL_ANNOTATIONS.contains(annName)) continue;

            ConditionalMetadata cond = buildConditionalMetadata(annName, ann);
            if (cond != null) {
                builder.addCondition(cond);
            }
        }

        BeanMetadata meta = builder.build();

        // Verify @Profile match
        if (!meta.activeProfiles().isEmpty() && !activeProfiles.isEmpty()) {
            boolean matchesProfile = meta.activeProfiles().stream()
                    .anyMatch(activeProfiles::contains);
            if (!matchesProfile) {
                log.debug("Skipping bean {} — profile {} not in active profiles {}",
                        className, meta.activeProfiles(), activeProfiles);
                return;
            }
        }

        // Evaluate @ConditionalOnProperty against loaded application properties
        for (ConditionalMetadata cond : meta.conditions()) {
            if (cond.type() == ConditionalMetadata.ConditionalType.ON_PROPERTY) {
                if (!evaluateConditionalOnProperty(cond, appProperties)) {
                    log.debug("Skipping bean {} — @ConditionalOnProperty({}) not satisfied",
                            className, cond.key());
                    return;
                }
            }
        }

        registry.registerBean(className, beanName, meta);
    }

    private void registerConfigBeans(ClassOrInterfaceDeclaration cls,
                                      String className, Path file) {
        // Register the configuration class itself
        String beanName = resolveBeanName(cls);
        BeanMetadata.Builder builder = BeanMetadata.builder(className, beanName, NodeType.CONFIGURATION);
        registry.registerBean(className, beanName, builder.build());

        // Register @Bean methods
        for (MethodDeclaration method : cls.getMethods()) {
            method.getAnnotationByName("Bean").ifPresent(beanAnn -> {
                String methodBeanName = extractBeanName(beanAnn, method);
                String returnType = method.getType().asString();

                // classify from the return type name, not the @Configuration class.
                // Previously every @Bean method got NodeType.CONFIGURATION regardless of what it returned.
                NodeType beanStereotype = classifyStereotypeFromTypeName(returnType);
                BeanMetadata.Builder mb = BeanMetadata.builder(returnType, methodBeanName, beanStereotype);

                method.getAnnotationByName("Primary")
                        .ifPresent(a -> mb.primary(true));
                method.getAnnotationByName("Qualifier").ifPresent(q -> {
                    if (q.isSingleMemberAnnotationExpr()) {
                        mb.qualifier(q.asSingleMemberAnnotationExpr()
                                .getMemberValue().toString().replaceAll("\"", ""));
                    }
                });
                method.getAnnotationByName("Profile").ifPresent(prof -> {
                    for (String p : extractStringValues(prof)) {
                        mb.addProfile(p);
                    }
                });

                BeanMetadata meta = mb.build();
                registry.registerBean(returnType, methodBeanName, meta);
            });
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isStereotypedOrBean(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> STEREOTYPE_ANNOTATIONS.contains(a.getNameAsString()));
    }

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

    /**
     * Infers a {@link NodeType} from a return type name so that
     * {@code @Bean} factory methods are not all classified as {@code CONFIGURATION}.
     * Falls back to {@link NodeType#COMPONENT} for unrecognised types.
     */
    private NodeType classifyStereotypeFromTypeName(String typeName) {
        String simple = typeName.contains(".")
                ? typeName.substring(typeName.lastIndexOf('.') + 1)
                : typeName;
        // Strip generic parameters e.g. "List<Order>" -> "List"
        int genericIdx = simple.indexOf('<');
        if (genericIdx > 0) simple = simple.substring(0, genericIdx);

        if (simple.endsWith("Repository") || simple.endsWith("Dao") || simple.endsWith("DAO"))
            return NodeType.REPOSITORY;
        if (simple.endsWith("Service"))
            return NodeType.SERVICE;
        if (simple.endsWith("Controller"))
            return NodeType.CONTROLLER;
        if (simple.endsWith("Configuration") || simple.endsWith("Config"))
            return NodeType.CONFIGURATION;
        return NodeType.COMPONENT;
    }


    private String resolveBeanName(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("Component").flatMap(c -> {
            if (c.isSingleMemberAnnotationExpr()) {
                return Optional.of(c.asSingleMemberAnnotationExpr()
                        .getMemberValue().toString().replaceAll("\"", ""));
            }
            return Optional.empty();
        }).orElse(cls.getAnnotationByName("Service").flatMap(c -> {
            if (c.isSingleMemberAnnotationExpr()) {
                return Optional.of(c.asSingleMemberAnnotationExpr()
                        .getMemberValue().toString().replaceAll("\"", ""));
            }
            return Optional.empty();
        }).orElse(cls.getAnnotationByName("Repository").flatMap(c -> {
            if (c.isSingleMemberAnnotationExpr()) {
                return Optional.of(c.asSingleMemberAnnotationExpr()
                        .getMemberValue().toString().replaceAll("\"", ""));
            }
            return Optional.empty();
        }).orElse(decapitalize(cls.getNameAsString()))));
    }

    private String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
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
            return beanAnn.asSingleMemberAnnotationExpr()
                    .getMemberValue().toString().replaceAll("\"", "");
        }
        return method.getNameAsString();
    }

    private Set<String> extractStringValues(AnnotationExpr ann) {
        Set<String> values = new LinkedHashSet<>();
        if (ann.isNormalAnnotationExpr()) {
            ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value"))
                    .findFirst()
                    .ifPresent(p -> {
                        String val = p.getValue().toString()
                                .replaceAll("[{}\"\\s]", "");
                        for (String v : val.split(",")) {
                            if (!v.isBlank()) values.add(v.trim());
                        }
                    });
        }
        if (ann.isSingleMemberAnnotationExpr()) {
            String val = ann.asSingleMemberAnnotationExpr()
                    .getMemberValue().toString().replaceAll("[{}\"\\s]", "");
            for (String v : val.split(",")) {
                if (!v.isBlank()) values.add(v.trim());
            }
        }
        // isArrayInitializerExpr() on an AnnotationExpr is always false —
        // AnnotationExpr is not a subtype of ArrayInitializerExpr. Dead code removed.
        return values;
    }

    private ConditionalMetadata buildConditionalMetadata(String annName, AnnotationExpr ann) {
        ConditionalMetadata.ConditionalType type = switch (annName) {
            case "ConditionalOnProperty" -> ConditionalMetadata.ConditionalType.ON_PROPERTY;
            case "ConditionalOnClass" -> ConditionalMetadata.ConditionalType.ON_CLASS;
            case "ConditionalOnMissingBean" -> ConditionalMetadata.ConditionalType.ON_MISSING_BEAN;
            case "ConditionalOnBean" -> ConditionalMetadata.ConditionalType.ON_BEAN;
            case "ConditionalOnExpression" -> ConditionalMetadata.ConditionalType.ON_EXPRESSION;
            default -> null;
        };

        if (type == null) return null;

        String key = "";
        String expectedValue = "";

        if (ann.isNormalAnnotationExpr()) {
            var normal = ann.asNormalAnnotationExpr();
            key = normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("name")
                            || p.getNameAsString().equals("value"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replaceAll("\"", ""))
                    .orElse("");
            expectedValue = normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("havingValue")
                            || p.getNameAsString().equals("matchIfMissing"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replaceAll("\"", ""))
                    .orElse("");
        }

        return new ConditionalMetadata(type, key, expectedValue);
    }

    private boolean isExcluded(String className, ClassOrInterfaceDeclaration cls,
                                Set<String> excludeFilters, Set<String> includeFilters) {
        if (!includeFilters.isEmpty()) {
            boolean included = includeFilters.stream().anyMatch(className::startsWith);
            if (!included) return true;
        }
        return excludeFilters.stream().anyMatch(f ->
                className.equals(f) || className.startsWith(f));
    }

    private boolean isInScanPath(String filePackage, Set<String> scanPackages) {
        if (scanPackages.isEmpty() || scanPackages.contains("")) return true;
        return scanPackages.stream().anyMatch(pkg ->
                filePackage.equals(pkg) || filePackage.startsWith(pkg + "."));
    }

    private String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse("");
    }

    private List<Path> collectJavaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    // ── Application property loading ─────────────────────────────────────

    /**
     * Scans the source tree for {@code application.properties} and
     * {@code application.yml} / {@code application.yaml} files and merges
     * key-value pairs into a single map for {@code @ConditionalOnProperty} evaluation.
     */
    private Map<String, String> loadApplicationProperties(Path sourceRoot) {
        Map<String, String> props = HashMap.newHashMap(32);
        if (!Files.isDirectory(sourceRoot)) return props;
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if ("application.properties".equals(name)) {
                        loadPropertiesFile(file, props);
                    } else if ("application.yml".equals(name) || "application.yaml".equals(name)) {
                        loadYamlFile(file, props);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan application properties: {}", e.getMessage());
        }
        return props;
    }

    private void loadPropertiesFile(Path file, Map<String, String> target) {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            p.load(reader);
            p.stringPropertyNames().forEach(key -> target.put(key, p.getProperty(key)));
            log.debug("Loaded {} properties from {}", p.size(), file);
        } catch (IOException e) {
            log.debug("Failed to load properties file: {}", file);
        }
    }

    /** Simple line-by-line YAML loader for flat {@code key: value} pairs. */
    private void loadYamlFile(Path file, Map<String, String> target) {
        try {
            Files.lines(file).forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) return;
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx <= 0) return;
                String key   = trimmed.substring(0, colonIdx).trim();
                String value = trimmed.substring(colonIdx + 1).trim();
                int commentIdx = value.indexOf(" #");
                if (commentIdx > 0) value = value.substring(0, commentIdx).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1)
                    value = value.substring(1, value.length() - 1);
                if (!key.isBlank()) target.put(key, value);
            });
        } catch (IOException e) {
            log.debug("Failed to load YAML file: {}", file);
        }
    }

    // ── @ConditionalOnProperty evaluation ────────────────────────────────

    /**
     * Evaluates a single {@code @ConditionalOnProperty} condition against
     * the loaded application properties.
     *
     * <p>{@code @ConditionalOnClass} and {@code @ConditionalOnMissingBean} are
     * not evaluated — they require a live classloader and remain as metadata only.
     */
    private boolean evaluateConditionalOnProperty(ConditionalMetadata cond,
                                                   Map<String, String> props) {
        String key           = cond.key();
        String expectedValue = cond.expectedValue();

        boolean matchIfMissing = expectedValue != null
                && expectedValue.toLowerCase().contains("matchifmissing=true");

        String actualValue = props.get(key);
        if (actualValue == null) return matchIfMissing;

        if (expectedValue == null || expectedValue.isBlank() || matchIfMissing) {
            return !actualValue.isBlank()
                    && !"false".equalsIgnoreCase(actualValue)
                    && !"0".equals(actualValue);
        }

        return expectedValue.equalsIgnoreCase(actualValue);
    }
}
