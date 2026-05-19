package com.specter.server;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.persistence.GraphSerializer;
import io.micrometer.core.instrument.MeterRegistry;

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
    private final MeterRegistry meterRegistry;

    public ProjectRegistry(Path cacheDir) {
        this(cacheDir, null);
    }

    public ProjectRegistry(Path cacheDir, MeterRegistry meterRegistry) {
        this.cacheDir = cacheDir;
        this.meterRegistry = meterRegistry;
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
     * <p>Uses {@code computeIfAbsent} so concurrent calls with the same
     * {@code sourceRoot} are atomic — only one analysis thread is ever spawned.
     */
    public ProjectContext registerProject(String sourceRoot, String displayName) throws IOException {
        Path root = Path.of(sourceRoot).toAbsolutePath();
        String projectId = GraphSerializer.projectHash(root);
        String name = displayName != null && !displayName.isBlank()
                ? displayName : root.getFileName().toString();

        // computeIfAbsent is atomic — no check-then-act race
        return projects.computeIfAbsent(projectId, id -> {
            try {
                SpecterAnalysisEngine engine = new SpecterAnalysisEngine(null, Set.of());
                ProjectContext ctx = new ProjectContext(id, name, root, engine,
                        Instant.now(), AnalysisStatus.PENDING);
                Thread.startVirtualThread(() -> runAnalysis(id, name, root, engine));
                return ctx;
            } catch (IOException e) {
                throw new RuntimeException("Failed to create analysis engine for " + root, e);
            }
        });
    }

    private void runAnalysis(String projectId, String name, Path root,
                              SpecterAnalysisEngine engine) {
        Path graphCache = cacheDir.resolve("graph-" + projectId + ".json");
        try {
            projects.put(projectId, new ProjectContext(
                    projectId, name, root, engine, Instant.now(), AnalysisStatus.ANALYZING));
            engine.analyze(root);
            GraphSerializer.saveToFile(engine.getGraph(), graphCache);
            projects.put(projectId, new ProjectContext(
                    projectId, name, root, engine, Instant.now(), AnalysisStatus.READY));
            log.info("Project '{}' analysis complete — {} nodes, {} edges",
                    name, engine.getGraph().nodeCount(), engine.getGraph().edgeCount());
            if (meterRegistry != null) {
                meterRegistry.counter("specter.projects.analyzed").increment();
            }
        } catch (IOException e) {
            log.error("Failed to analyze project '{}'", name, e);
            projects.put(projectId, new ProjectContext(
                    projectId, name, root, engine, Instant.now(), AnalysisStatus.FAILED));
        }
    }

    public ProjectContext getProject(String projectId) {
        return projects.get(projectId);
    }

    public Collection<ProjectContext> listProjects() {
        return Collections.unmodifiableCollection(projects.values());
    }
}

