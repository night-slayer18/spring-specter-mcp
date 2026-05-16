package com.specter.server;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.specter.core.SpecterAnalysisEngine;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer;
import com.specter.core.analysis.ArchitecturalHealthAnalyzer.DimensionScore;
import com.specter.core.analysis.RiskScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI architecture gate. Run with:
 * {@code mvn test -pl specter-server -Dtest=ArchitectureGateTest -Dspecter.source.root=/path/to/project}
 */
public class ArchitectureGateTest {

    @Test
    @EnabledIfSystemProperty(named = "specter.source.root", matches = ".+")
    void architectureGate_PassOrFail_OverallScoreMustBe65OrAbove() throws Exception {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);

        Path sourceRoot = Path.of(System.getProperty("specter.source.root")).toAbsolutePath();

        assertTrue(Files.isDirectory(sourceRoot),
                "Source root does not exist: " + sourceRoot);

        SpecterAnalysisEngine engine = new SpecterAnalysisEngine();
        try {
            engine.analyze(sourceRoot);

            ArchitecturalHealthAnalyzer healthAnalyzer =
                    new ArchitecturalHealthAnalyzer(engine.getGraph());
            ArchitecturalHealthAnalyzer.HealthReport report = healthAnalyzer.analyze();

            int score = report.overallScore();
            System.out.println("=== Architecture Gate ===");
            System.out.println("Overall Score: " + score + "/100");
            System.out.println("Graph: " + engine.getGraph().nodeCount() + " nodes, "
                    + engine.getGraph().edgeCount() + " edges");
            System.out.println("Active beans: " + engine.getRegistry().size());
            System.out.println("Critical issues: " + report.criticalIssues().size());
            System.out.println("Dimensions:");

            for (DimensionScore dim : report.dimensions().values()) {
                System.out.printf("  %s: %d/100 (%d issues)%n",
                        dim.name(), dim.score(), dim.criticalIssues().size());
            }

            for (RiskScore risk : report.criticalIssues()) {
                System.out.printf("  [%s] %s — %s%n",
                        risk.level(), risk.nodeId(), risk.reason());
            }

            System.out.println("=========================");

            // Write health report as JSON for CI PR comment
            writeHealthReport(report, engine);

            assertTrue(score >= 65,
                    "Architecture health score " + score + " is below threshold 65. "
                            + "Fix " + report.criticalIssues().size() + " critical issues.");

        } finally {
            engine.close();
        }
    }

    private void writeHealthReport(ArchitecturalHealthAnalyzer.HealthReport report,
                                   SpecterAnalysisEngine engine) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"overallScore\": ").append(report.overallScore()).append(",\n");
            json.append("  \"nodeCount\": ").append(engine.getGraph().nodeCount()).append(",\n");
            json.append("  \"edgeCount\": ").append(engine.getGraph().edgeCount()).append(",\n");
            json.append("  \"dimensions\": {");
            boolean firstDim = true;
            for (var entry : report.dimensions().entrySet()) {
                if (!firstDim) json.append(",");
                firstDim = false;
                var dim = entry.getValue();
                json.append("\n    \"").append(escapeJson(entry.getKey())).append("\": {");
                json.append("\n      \"name\": \"").append(escapeJson(dim.name())).append("\",");
                json.append("\n      \"score\": ").append(dim.score());
                json.append("\n    }");
            }
            json.append("\n  },\n");

            json.append("  \"criticalIssues\": [");
            boolean firstIssue = true;
            for (var risk : report.criticalIssues()) {
                if (!firstIssue) json.append(",");
                firstIssue = false;
                json.append("\n    {");
                json.append("\n      \"nodeId\": \"").append(escapeJson(risk.nodeId())).append("\",");
                json.append("\n      \"reason\": \"").append(escapeJson(risk.reason())).append("\",");
                json.append("\n      \"score\": ").append(risk.score()).append(",");
                json.append("\n      \"level\": \"").append(risk.level().name()).append("\"");
                json.append("\n    }");
            }
            json.append("\n  ],\n");

            json.append("  \"recommendations\": [");
            boolean firstRec = true;
            for (String r : report.recommendations()) {
                if (!firstRec) json.append(",");
                firstRec = false;
                json.append("\n    \"").append(escapeJson(r)).append("\"");
            }
            json.append("\n  ]\n");
            json.append("}\n");

            Path reportPath = Path.of(".specter-cache", "health-report.json");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, json.toString());
            System.out.println("Health report written to " + reportPath);
        } catch (Exception e) {
            System.out.println("Failed to write health report: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
