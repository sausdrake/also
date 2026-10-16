package com.example.autoauction.diagnostic.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class InspectionItem {
    private Long id;
    private Long groupId;
    private String title;
    private String description;
    private String fieldType;
    private boolean hasPhoto;
    private boolean hasVideo;
    private boolean hasRating;
    @JsonProperty("isRequired")
    private boolean isRequired;

    // SEC-15: explicit, so the getter and the field stay ONE JSON property (see InspectionTemplate).
    @JsonProperty("isRequired")
    public boolean isRequired() {
        return isRequired;
    }
    private Integer sortOrder;
    private OffsetDateTime createdAt;
}