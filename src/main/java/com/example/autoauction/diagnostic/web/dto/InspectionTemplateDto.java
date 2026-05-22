package com.example.autoauction.diagnostic.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InspectionTemplateDto {
    @NotBlank(message = "Название шаблона обязательно")
    private String name;

    private String vehicleType;
    private String description;
}