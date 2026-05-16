package com.specter.core.watcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks file modification state to enable incremental re-analysis.
 * Compares current file system state against persisted fingerprints,
 * returning only the files that have changed since the last analysis run.
 */
@Slf4j
public class SourceChangeTracker {

    private final Map<Path, FileFingerprint> fingerprints = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path cacheDir;
    private final Path fingerprintsFile;

    public SourceChangeTracker(Path projectRoot) {
        this.cacheDir = projectRoot.resolve(".specter-cache");
        this.fingerprintsFile = cacheDir.resolve("fingerprints.json");
    }

    /**
     * Computes the set of files that have been added, modified, or deleted
     * since the last tracked state. On first run (no cache file), all files
     * are marked as added.
     */
    public ChangeSet computeChanges(Path sourceRoot) throws IOException {
        loadFingerprints();
        Map<Path, FileFingerprint> current = buildCurrentFingerprints(sourceRoot);

        Set<Path> added = new HashSet<>();
        Set<Path> modified = new HashSet<>();
        Set<Path> deleted = new HashSet<>(fingerprints.keySet());

        for (var entry : current.entrySet()) {
            Path file = entry.getKey();
            FileFingerprint newFp = entry.getValue();
            FileFingerprint oldFp = fingerprints.get(file);

            deleted.remove(file);

            if (oldFp == null) {
                added.add(file);
            } else if (!newFp.equals(oldFp)) {
                modified.add(file);
            }
        }

        ChangeSet changeSet = new ChangeSet(
                Collections.unmodifiableSet(added),
                Collections.unmodifiableSet(modified),
                Collections.unmodifiableSet(deleted)
        );

        log.info("Change detection: +{} added, ~{} modified, -{} deleted",
                added.size(), modified.size(), deleted.size());

        return changeSet;
    }

    /**
     * Persists the current file fingerprints to disk for future comparison.
     */
    public void persistFingerprints(Path sourceRoot) throws IOException {
        Map<Path, FileFingerprint> current = buildCurrentFingerprints(sourceRoot);
        fingerprints.clear();
        fingerprints.putAll(current);

        Files.createDirectories(cacheDir);
        mapper.writeValue(fingerprintsFile.toFile(), fingerprints);
        log.debug("Persisted {} fingerprints to {}", fingerprints.size(), fingerprintsFile);
    }

    @SuppressWarnings("unchecked")
    private void loadFingerprints() {
        fingerprints.clear();
        if (!Files.exists(fingerprintsFile)) return;

        try {
            Map<String, Map<String, Object>> raw = mapper.readValue(
                    fingerprintsFile.toFile(),
                    new TypeReference<Map<String, Map<String, Object>>>() {});

            for (var entry : raw.entrySet()) {
                Path file = Path.of(entry.getKey());
                Map<String, Object> data = entry.getValue();
                long lastModifiedMs = ((Number) data.get("lastModifiedMs")).longValue();
                long sizeBytes = ((Number) data.get("sizeBytes")).longValue();
                String sha256 = (String) data.get("sha256");
                fingerprints.put(file, new FileFingerprint(file, lastModifiedMs, sizeBytes, sha256));
            }
        } catch (IOException e) {
            log.warn("Failed to load fingerprints from {}: {}", fingerprintsFile, e.getMessage());
        }
    }

    private Map<Path, FileFingerprint> buildCurrentFingerprints(Path sourceRoot) throws IOException {
        Map<Path, FileFingerprint> map = new HashMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList())) {
                try {
                    long lastModifiedMs = Files.getLastModifiedTime(file).toMillis();
                    long sizeBytes = Files.size(file);
                    FileFingerprint oldFp = fingerprints.get(file);
                    String sha256;

                    if (oldFp != null && oldFp.lastModifiedMs() == lastModifiedMs
                            && oldFp.sizeBytes() == sizeBytes) {
                        sha256 = oldFp.sha256();
                    } else {
                        sha256 = computeSha256(file);
                    }

                    map.put(file, new FileFingerprint(file, lastModifiedMs, sizeBytes, sha256));
                } catch (IOException e) {
                    log.debug("Failed to fingerprint file: {}", file, e);
                }
            }
        }
        return map;
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Describes the fingerprint of a single source file for change detection.
     */
    public record FileFingerprint(Path path, long lastModifiedMs, long sizeBytes, String sha256) {
        public FileFingerprint {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    /**
     * Represents the set of changes detected since the last analysis run.
     */
    public record ChangeSet(
            Set<Path> added,
            Set<Path> modified,
            Set<Path> deleted
    ) {
        public boolean hasChanges() {
            return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        }

        public int totalChanges() {
            return added.size() + modified.size() + deleted.size();
        }
    }
}
