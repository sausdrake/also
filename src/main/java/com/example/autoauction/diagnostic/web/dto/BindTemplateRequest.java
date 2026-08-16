package com.example.autoauction.diagnostic.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BindTemplateRequest {

    @NotNull(message = "templateId обязателен")
    private Long templateId;
}
