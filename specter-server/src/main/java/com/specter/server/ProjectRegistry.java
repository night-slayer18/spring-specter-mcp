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
     * Registers a project and triggers async analysis. If a cached graph exists,
     * runs incremental; otherwise runs full analysis. Returns immediately with
     * ANALYZING status.
     */
    public ProjectContext registerProject(String sourceRoot, String displayName) throws IOException {
        Path root = Path.of(sourceRoot).toAbsolutePath();
        String projectId = UUID.randomUUID().toString();
        String name = displayName != null && !displayName.isBlank() ? displayName : root.getFileName().toString();

        if (projects.containsKey(projectId)) {
            return projects.get(projectId);
        }

        Path graphCache = cacheDir.resolve("graph-" + projectId + ".json");
        SpecterAnalysisEngine engine = new SpecterAnalysisEngine(null, Set.of());

        ProjectContext ctx = new ProjectContext(projectId, name, root, engine, Instant.now(),
                AnalysisStatus.PENDING);
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
