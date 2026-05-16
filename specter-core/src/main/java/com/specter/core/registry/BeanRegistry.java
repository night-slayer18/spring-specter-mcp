package com.specter.core.registry;

import com.specter.core.graph.NodeType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared context built during Pass 1 component-scan simulation.
 * Stores which beans Spring would actually instantiate, their metadata,
 * and provides resolution logic for {@code @Primary} / {@code @Qualifier}
 * disambiguation during Pass 2 dependency injection.
 */
public class BeanRegistry {

    private final Set<String> activeBeanClasses = ConcurrentHashMap.newKeySet();

    private final Map<String, String> beanNameToClass = new ConcurrentHashMap<>();

    private final Map<String, BeanMetadata> classMetadata = new ConcurrentHashMap<>();

    public void registerBean(String qualifiedName, String beanName, BeanMetadata metadata) {
        activeBeanClasses.add(qualifiedName);
        beanNameToClass.put(beanName, qualifiedName);
        classMetadata.put(qualifiedName, metadata);
    }

    public boolean isActive(String qualifiedName) {
        return activeBeanClasses.contains(qualifiedName);
    }

    public Optional<String> resolveBeanClass(String beanName) {
        return Optional.ofNullable(beanNameToClass.get(beanName));
    }

    public Optional<BeanMetadata> getMetadata(String qualifiedName) {
        return Optional.ofNullable(classMetadata.get(qualifiedName));
    }

    /**
     * Resolves which concrete implementation Spring would inject for a given
     * type, accounting for {@code @Primary} and {@code @Qualifier} annotations.
     *
     * @param typeName   the declared field/parameter type (may be an interface)
     * @param qualifier  the {@code @Qualifier} value, or {@code null} if none
     * @return the resolved qualified class name, or empty if ambiguous/unresolvable
     */
    public Optional<String> resolveInjectionTarget(String typeName, String qualifier) {
        List<String> candidates = new ArrayList<>();

        for (String activeClass : activeBeanClasses) {
            BeanMetadata meta = classMetadata.get(activeClass);
            if (meta == null) continue;

            // Exact match
            if (activeClass.equals(typeName)) {
                candidates.add(activeClass);
                continue;
            }

            // Interface match via implements
            if (meta.interfaces().contains(typeName)) {
                candidates.add(activeClass);
            }
        }

        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        // Multiple candidates — disambiguate
        if (qualifier != null && !qualifier.isBlank()) {
            for (String candidate : candidates) {
                BeanMetadata meta = classMetadata.get(candidate);
                if (meta != null && qualifier.equals(meta.qualifier())) {
                    return Optional.of(candidate);
                }
                if (beanNameToClass.get(qualifier) != null
                        && beanNameToClass.get(qualifier).equals(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }

        // Check @Primary
        for (String candidate : candidates) {
            BeanMetadata meta = classMetadata.get(candidate);
            if (meta != null && meta.primary()) {
                return Optional.of(candidate);
            }
        }

        // Ambiguous — return empty; caller should mark as ambiguous
        return Optional.empty();
    }

    public Set<String> activeClasses() {
        return Collections.unmodifiableSet(activeBeanClasses);
    }

    public int size() {
        return activeBeanClasses.size();
    }

    // ── Bean metadata record ────────────────────────────────────────────

    public record BeanMetadata(
            String qualifiedName,
            String beanName,
            NodeType stereotype,
            Set<String> interfaces,
            boolean primary,
            String qualifier,
            String scope,
            Set<String> activeProfiles,
            List<ConditionalMetadata> conditions
    ) {
        public boolean isActive() {
            return activeProfiles == null || activeProfiles.isEmpty();
        }

        public BeanMetadata withQualifier(String q) {
            return new BeanMetadata(qualifiedName, beanName, stereotype,
                    interfaces, primary, q, scope, activeProfiles, conditions);
        }

        public static Builder builder(String qualifiedName, String beanName, NodeType stereotype) {
            return new Builder(qualifiedName, beanName, stereotype);
        }

        public static class Builder {
            private final String qualifiedName;
            private final String beanName;
            private final NodeType stereotype;
            private final Set<String> interfaces = new HashSet<>();
            private boolean primary;
            private String qualifier;
            private String scope = "singleton";
            private final Set<String> activeProfiles = new HashSet<>();
            private final List<ConditionalMetadata> conditions = new ArrayList<>();

            Builder(String qualifiedName, String beanName, NodeType stereotype) {
                this.qualifiedName = qualifiedName;
                this.beanName = beanName;
                this.stereotype = stereotype;
            }

            public Builder addInterface(String iface) { interfaces.add(iface); return this; }
            public Builder primary(boolean p) { primary = p; return this; }
            public Builder qualifier(String q) { qualifier = q; return this; }
            public Builder scope(String s) { scope = s; return this; }
            public Builder addProfile(String profile) { activeProfiles.add(profile); return this; }
            public Builder addCondition(ConditionalMetadata c) { conditions.add(c); return this; }

            public BeanMetadata build() {
                return new BeanMetadata(qualifiedName, beanName, stereotype,
                        Set.copyOf(interfaces), primary, qualifier, scope,
                        Set.copyOf(activeProfiles), List.copyOf(conditions));
            }
        }
    }

    // ── Conditional metadata ────────────────────────────────────────────

    public record ConditionalMetadata(
            ConditionalType type,
            String key,
            String expectedValue
    ) {
        public enum ConditionalType {
            ON_PROPERTY,
            ON_CLASS,
            ON_MISSING_BEAN,
            ON_BEAN,
            ON_EXPRESSION
        }
    }
}
