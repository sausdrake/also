package com.example.autoauction.diagnostic.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectionAnswerDto {
    private Long itemId;
    private Integer rating;
    private String comment;
    private List<String> photoUrls;
    private List<String> videoUrls;
    /** Scenario 3: "Пропустить" - allowed only for a non-required item. */
    private boolean skipped;
}