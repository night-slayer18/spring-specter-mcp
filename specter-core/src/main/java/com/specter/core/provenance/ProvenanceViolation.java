package com.specter.core.provenance;


public record ProvenanceViolation(
    String commitHash,
    String message,
    ViolationType type
) {
    public enum ViolationType {
        GPG_FORBIDDEN,
        NO_SSH_SIGNATURE,
        INVALID_SSH_SIGNATURE
    }
}
