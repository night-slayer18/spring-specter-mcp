package com.specter.core.watcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks file modification state to enable incremental re-analysis.
 * Compares current file system state against persisted fingerprints,
 * returning only the files that have changed since the last analysis run.
 *
 * <h3>Serialization</h3>
 * Fingerprints are persisted as {@code Map<String, FingerprintDto>} (path string → DTO)
 * because Jackson cannot serialize {@link Path} objects as map keys without a custom
 * module. In-memory, the working set is kept as {@code Map<Path, FileFingerprint>}.
 *
 * <h3>File scanning</h3>
 * Uses {@link Files#walkFileTree} with a {@link SimpleFileVisitor} to avoid
 * materialising the full file list before processing, and reads
 * {@link BasicFileAttributes} in a single syscall per file.
 */
@Slf4j
public class SourceChangeTracker {

    /** Serialization-friendly DTO — avoids the Jackson {@code Path}-as-key problem. */
    private record FingerprintDto(long lastModifiedMs, long sizeBytes, String sha256) {}

    private final Map<Path, FileFingerprint> fingerprints = new ConcurrentHashMap<>(256);
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
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

        Set<Path> added    = new HashSet<>();
        Set<Path> modified = new HashSet<>();
        Set<Path> deleted  = new HashSet<>(fingerprints.keySet());

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
                Collections.unmodifiableSet(deleted));

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

        // Serialize as Map<String, FingerprintDto> — Path is not a valid Jackson map key
        Map<String, FingerprintDto> serializable = HashMap.newHashMap(fingerprints.size());
        fingerprints.forEach((path, fp) ->
                serializable.put(path.toAbsolutePath().toString(),
                        new FingerprintDto(fp.lastModifiedMs(), fp.sizeBytes(), fp.sha256())));

        Files.createDirectories(cacheDir);
        mapper.writeValue(fingerprintsFile.toFile(), serializable);
        log.debug("Persisted {} fingerprints to {}", fingerprints.size(), fingerprintsFile);
    }

    private void loadFingerprints() {
        fingerprints.clear();
        if (!Files.exists(fingerprintsFile)) return;

        try {
            Map<String, FingerprintDto> raw = mapper.readValue(
                    fingerprintsFile.toFile(),
                    new TypeReference<Map<String, FingerprintDto>>() {});

            raw.forEach((pathStr, dto) -> {
                Path file = Path.of(pathStr);
                fingerprints.put(file, new FileFingerprint(file,
                        dto.lastModifiedMs(), dto.sizeBytes(), dto.sha256()));
            });
        } catch (IOException e) {
            log.warn("Failed to load fingerprints from {}: {}", fingerprintsFile, e.getMessage());
        }
    }

    /**
     * Walks the source tree using {@link Files#walkFileTree} — reads
     * {@link BasicFileAttributes} in a single syscall per file, re-using
     * a cached SHA-256 when mtime and size are unchanged.
     */
    private Map<Path, FileFingerprint> buildCurrentFingerprints(Path sourceRoot) throws IOException {
        Map<Path, FileFingerprint> map = HashMap.newHashMap(256);

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                try {
                    long lastModifiedMs = attrs.lastModifiedTime().toMillis();
                    long sizeBytes      = attrs.size();
                    FileFingerprint oldFp = fingerprints.get(file);
                    String sha256;

                    if (oldFp != null
                            && oldFp.lastModifiedMs() == lastModifiedMs
                            && oldFp.sizeBytes() == sizeBytes) {
                        sha256 = oldFp.sha256(); // cache hit — skip digest
                    } else {
                        sha256 = computeSha256(file);
                    }

                    map.put(file, new FileFingerprint(file, lastModifiedMs, sizeBytes, sha256));
                } catch (IOException e) {
                    log.debug("Failed to fingerprint file: {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.debug("Skipping unreadable file: {}", file);
                return FileVisitResult.CONTINUE;
            }
        });

        return map;
    }

    private String computeSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ── Value records ────────────────────────────────────────────────────

    /**
     * Fingerprint of a single source file for change detection.
     */
    public record FileFingerprint(Path path, long lastModifiedMs, long sizeBytes, String sha256) {
        public FileFingerprint {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    /**
     * Represents the set of changes detected since the last analysis run.
     */
    public record ChangeSet(Set<Path> added, Set<Path> modified, Set<Path> deleted) {
        public boolean hasChanges() {
            return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
        }
        public int totalChanges() {
            return added.size() + modified.size() + deleted.size();
        }
    }
}
