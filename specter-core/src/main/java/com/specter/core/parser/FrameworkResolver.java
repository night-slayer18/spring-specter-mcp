package com.specter.core.parser;

import com.specter.core.graph.SpecterGraph;

import java.io.IOException;
import java.nio.file.Path;

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
     * Returns a human-readable name for this resolver phase,
     * used in pipeline logging.
     */
    String name();
}
