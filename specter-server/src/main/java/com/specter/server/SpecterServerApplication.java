package com.specter.server;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.server.streaming.GraphChangePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class SpecterServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpecterServerApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public SpecterAnalysisEngine analysisEngine(
            @Value("${specter.classes.root:#{null}}") String classesRootPath,
            @Value("${specter.active.profiles:}") String activeProfilesCsv) throws IOException {
        Path classesRoot = (classesRootPath != null && !classesRootPath.isBlank())
                ? Path.of(classesRootPath).toAbsolutePath()
                : null;
        Set<String> activeProfiles = parseProfiles(activeProfilesCsv);
        return new SpecterAnalysisEngine(classesRoot, activeProfiles);
    }

    @Bean
    public ProjectRegistry projectRegistry() {
        Path cacheDir = Path.of(".specter-cache").toAbsolutePath();
        return new ProjectRegistry(cacheDir);
    }

    @Bean
    public ApplicationRunner analysisRunner(
            SpecterAnalysisEngine engine,
            GraphChangePublisher publisher,
            @Value("${specter.source.root:./src}") String sourceRootPath,
            @Value("${specter.active.profiles:}") String activeProfilesCsv) {
        return args -> {
            Path sourceRoot = Path.of(sourceRootPath).toAbsolutePath();
            if (!Files.isDirectory(sourceRoot)) {
                log.warn("Source root does not exist: {}. Skipping startup analysis. " +
                        "Set SPECTER_SOURCE_ROOT to a valid directory.", sourceRoot);
                return;
            }
            Set<String> activeProfiles = parseProfiles(activeProfilesCsv);
            engine.setProgressListener(publisher);
            log.info("Pre-warming analysis engine from: {} (profiles: {})",
                    sourceRoot, activeProfiles);
            engine.analyze(sourceRoot, activeProfiles);
            log.info("Analysis engine ready — {} nodes, {} edges, {} active beans",
                    engine.getGraph().nodeCount(),
                    engine.getGraph().edgeCount(),
                    engine.getRegistry().size());

            ArchitecturalHealthAnalyzer healthAnalyzer =
                    new ArchitecturalHealthAnalyzer(engine.getGraph());
            var report = healthAnalyzer.analyze();
            publisher.publishHealthUpdate(report);
            log.info("Published initial health score: {}", report.overallScore());
        };
    }

    private Set<String> parseProfiles(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> profiles = new java.util.LinkedHashSet<>();
        for (String p : csv.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isBlank()) profiles.add(trimmed);
        }
        return Collections.unmodifiableSet(profiles);
    }
}
