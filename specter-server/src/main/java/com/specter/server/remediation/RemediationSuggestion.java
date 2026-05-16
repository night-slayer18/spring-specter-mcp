package com.specter.server.remediation;

import java.util.List;

public record RemediationSuggestion(
    String nodeId,
    String issue,
    String suggestion,
    List<String> codeSnippets,
    boolean downtimeRequired,
    String effortEstimate
) {}
