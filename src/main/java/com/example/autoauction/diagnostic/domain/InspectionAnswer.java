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
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}