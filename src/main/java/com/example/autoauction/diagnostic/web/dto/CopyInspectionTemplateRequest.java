package com.example.autoauction.diagnostic.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Scenario 14.4: the name of the new template created as a copy of an existing one. */
public record CopyInspectionTemplateRequest(
        @NotBlank(message = "Название нового шаблона обязательно")
        String name
) {
}
