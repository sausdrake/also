package com.example.autoauction.diagnostic.domain;

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
    private boolean isActive;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<InspectionGroup> groups = new ArrayList<>();
}