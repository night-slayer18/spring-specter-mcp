package com.specter.server;

import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.server.streaming.GraphChangePublisher;
import com.specter.server.tools.SpecterMcpTools;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
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

    @Bean
    public ToolCallbackProvider specterToolCallbackProvider(SpecterMcpTools specterMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(specterMcpTools)
                .build();
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
    public ProjectRegistry projectRegistry(MeterRegistry meterRegistry) {
        Path cacheDir = Path.of(".specter-cache").toAbsolutePath();
        return new ProjectRegistry(cacheDir, meterRegistry);
    }

    @Bean
    public ApplicationRunner analysisRunner(
            SpecterAnalysisEngine engine,
            GraphChangePublisher publisher,
            @Value("${specter.source.root:./src}") String sourceRootPath,
            @Value("${specter.active.profiles:}") String activeProfilesCsv,
            @Value("${spring.main.web-application-type:none}") String webAppType) {
        return args -> {
            Path sourceRoot = Path.of(sourceRootPath).toAbsolutePath();
            if (Files.isDirectory(sourceRoot)) {
                Set<String> activeProfiles = parseProfiles(activeProfilesCsv);
                engine.setProgressListener(publisher);
                log.info("Pre-warming analysis engine from: {} (profiles: {}, web-type: {})",
                        sourceRoot, activeProfiles, webAppType);
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
            } else {
                log.warn("Source root does not exist: {}. Skipping startup analysis. " +
                        "Set SPECTER_SOURCE_ROOT to a valid directory.", sourceRoot);
            }

            if ("none".equals(webAppType)) {
                log.info("STDIO mode — blocking main thread to keep JVM alive");
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                log.info("Web mode ({}) — web server threads keep JVM alive", webAppType);
            }
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
