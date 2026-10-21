package com.example.autoauction.auction.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "Запрос на создание ставки")
public record BidRequest(

        @Schema(description = "Сумма ставки", example = "1050000", required = true)
        @NotNull(message = "Сумма ставки обязательна")
        @DecimalMin(value = "0.01", message = "Сумма ставки должна быть больше 0")
        // AUCTION-01: technical capacity of NUMERIC(10,2) and the tyiyn scale - never a silent DB rounding or a 500
        @Digits(integer = 8, fraction = 2, message = "Сумма ставки: не больше 99999999.99 и не больше двух знаков после запятой")
        BigDecimal amount
) {}