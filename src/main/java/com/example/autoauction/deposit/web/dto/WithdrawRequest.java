package com.example.autoauction.deposit.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "Запрос на вывод средств с депозита")
public record WithdrawRequest(

        @Schema(description = "Сумма", example = "10000", required = true)
        @NotNull(message = "Сумма обязательна")
        @DecimalMin(value = "0.01", message = "Сумма должна быть больше 0")
        BigDecimal amount,

        @Schema(description = "Ключ идемпотентности: клиент генерирует его один раз на попытку " +
                "вывода и переиспользует при повторной отправке того же запроса (например, после " +
                "сетевой ошибки), чтобы повтор не привёл к повторному списанию", required = true)
        @NotBlank(message = "Ключ идемпотентности обязателен")
        String idempotencyKey
) {}
