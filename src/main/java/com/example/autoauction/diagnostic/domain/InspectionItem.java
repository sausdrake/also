package com.example.autoauction.diagnostic.domain;

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class InspectionItem {
    private Long id;
    private Long groupId;
    private String title;
    private String description;
    private boolean hasPhoto;
    private boolean hasVideo;
    private boolean hasRating;
    private boolean isRequired;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
}