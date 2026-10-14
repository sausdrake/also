package com.example.autoauction.diagnostic.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class InspectionTemplate {
    private Long id;
    private String name;
    private String vehicleType;
    private String description;
    // Serialized as "isActive" - the admin client reads and writes that name (as InspectionItem
    // does for isRequired); Lombok's isActive() getter alone would publish it as "active".
    @JsonProperty("isActive")
    private boolean isActive;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<InspectionGroup> groups = new ArrayList<>();
}