package com.specter.core.parser;

import com.specter.core.graph.SpecterGraph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Common contract for all framework resolvers in the Specter analysis pipeline.
 * Each resolver scans the source tree (and optionally compiled artifacts) to
 * populate the shared {@link SpecterGraph} with nodes and edges representing
 * specific framework abstractions.
 */
public interface FrameworkResolver {

    /**
     * Executes resolution against the given source root, mutating the
     * shared graph.
     *
     * @param sourceRoot root directory of the project source tree
     * @throws IOException if file scanning or parsing fails
     */
    void resolve(Path sourceRoot) throws IOException;

    /**
     * Executes resolution scoped to a specific set of changed files.
     * Default implementation falls back to {@link #resolve(Path)} for
     * backward compatibility with resolvers that don't support
     * incremental analysis.
     *
     * @param files the Java source files that have been added or modified
     */
    default void resolveFiles(Collection<Path> files) throws IOException {
        throw new UnsupportedOperationException(
                "This resolver does not support incremental analysis");
    }

    /**
     * Returns a human-readable name for this resolver phase,
     * used in pipeline logging.
     */
    String name();
}
