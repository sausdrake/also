package com.example.autoauction.diagnostic.domain;

import lombok.Data;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class InspectionGroup {
    private Long id;
    private Long templateId;
    private String name;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private List<InspectionItem> items = new ArrayList<>();
}