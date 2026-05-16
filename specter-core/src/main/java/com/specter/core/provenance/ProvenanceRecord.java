package com.specter.core.provenance;

import java.time.Instant;

public record ProvenanceRecord(
    String commitHash,
    String message,
    String authorName,
    String authorEmail,
    Instant timestamp,
    String signingKey,
    SignatureType signatureType
) {
    public enum SignatureType {
        SSH,
        NONE
    }
}
