package com.specter.server;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.persistence.GraphSerializer;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Manages multiple {@link SpecterAnalysisEngine} instances — one per registered
 * project. Supports concurrent analysis via virtual threads and persistent
 * graph caching to disk.
 *
 * <p>Project IDs are derived deterministically from the normalized source-root
 * path hash, making {@link #registerProject} idempotent: calling it twice
 * with the same path returns the existing context immediately.
 */
@Slf4j
public class ProjectRegistry {

    private final ConcurrentHashMap<String, ProjectContext> projects = new ConcurrentHashMap<>();
    private final Path cacheDir;

    public ProjectRegistry(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public record ProjectContext(
            String projectId,
            String displayName,
            Path sourceRoot,
            SpecterAnalysisEngine engine,
            Instant lastAnalyzed,
            AnalysisStatus status
    ) {}

    public enum AnalysisStatus { PENDING, ANALYZING, READY, FAILED }

    /**
     * Registers a project and triggers async analysis.
     *
     * <p>The {@code projectId} is derived from a SHA-256 prefix of the
     * normalized source-root path — calling this method twice with the
     * same {@code sourceRoot} is therefore idempotent and returns the
     * existing {@link ProjectContext} without spawning a second analysis thread.
     *
     * @param sourceRoot  absolute path to the project source root
     * @param displayName optional human-readable name; defaults to directory name
     */
    public ProjectContext registerProject(String sourceRoot, String displayName) throws IOException {
        Path root = Path.of(sourceRoot).toAbsolutePath();
        // Deterministic ID — deduplication actually works
        String projectId = GraphSerializer.projectHash(root);
        String name = displayName != null && !displayName.isBlank()
                ? displayName : root.getFileName().toString();

        // Idempotent: return existing context if already registered
        ProjectContext existing = projects.get(projectId);
        if (existing != null) {
            log.debug("Project '{}' already registered — returning existing context", name);
            return existing;
        }

        Path graphCache = cacheDir.resolve("graph-" + projectId + ".json");
        SpecterAnalysisEngine engine = new SpecterAnalysisEngine(null, Set.of());

        ProjectContext ctx = new ProjectContext(projectId, name, root, engine,
                Instant.now(), AnalysisStatus.PENDING);
        projects.put(projectId, ctx);

        Thread.startVirtualThread(() -> {
            try {
                projects.put(projectId, new ProjectContext(
                        projectId, name, root, engine, Instant.now(), AnalysisStatus.ANALYZING));
                engine.analyze(root);
                GraphSerializer.saveToFile(engine.getGraph(), graphCache);
                projects.put(projectId, new ProjectContext(
                        projectId, name, root, engine, Instant.now(), AnalysisStatus.READY));
                log.info("Project '{}' analysis complete — {} nodes, {} edges",
                        name, engine.getGraph().nodeCount(), engine.getGraph().edgeCount());
            } catch (IOException e) {
                log.error("Failed to analyze project '{}'", name, e);
                projects.put(projectId, new ProjectContext(
                        projectId, name, root, engine, Instant.now(), AnalysisStatus.FAILED));
            }
        });

        return projects.get(projectId);
    }

    public ProjectContext getProject(String projectId) {
        return projects.get(projectId);
    }

    public Collection<ProjectContext> listProjects() {
        return Collections.unmodifiableCollection(projects.values());
    }
}
