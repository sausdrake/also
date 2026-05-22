package com.example.autoauction.diagnostic.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InspectionGroupDto {
    @NotBlank(message = "Название группы обязательно")
    private String name;

    private Integer sortOrder = 0;
}