package com.example.autoauction.diagnostic.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDiagnosticRequest {

    @NotBlank(message = "Марка обязательна")
    @Size(min = 2, max = 100)
    private String brand;

    @NotBlank(message = "Модель обязательна")
    @Size(min = 1, max = 100)
    private String model;

    @NotNull(message = "Год выпуска обязателен")
    private Integer year;

    @NotBlank(message = "VIN обязателен")
    @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$", message = "VIN должен содержать 17 символов")
    private String vin;
}