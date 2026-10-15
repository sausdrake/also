package com.example.autoauction.diagnostic.domain;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class InspectionAnswer {
    private Long id;
    private Long diagnosticRequestId;
    private Long inspectionItemId;
    private Integer rating;
    private String comment;
    private List<String> photoUrls;
    private List<String> videoUrls;
    /** Scenario 3: the diagnostician skipped this (non-required) item - it carries no content. */
    private boolean skipped;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}