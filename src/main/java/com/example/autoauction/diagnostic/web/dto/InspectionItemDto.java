package com.example.autoauction.diagnostic.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InspectionItemDto {
    @NotBlank(message = "Название пункта обязательно")
    private String title;

    private String description;
    private String fieldType = "TEXT";
    private boolean hasPhoto = false;
    private boolean hasVideo = false;
    private boolean hasRating = false;
    @JsonProperty("isRequired")
    private boolean isRequired = true;
    private Integer sortOrder = 0;
}