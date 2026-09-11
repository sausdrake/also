package com.example.autoauction.deposit.web.dto;

import com.example.autoauction.deposit.domain.AdminAdjustmentReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Slice 2 · Step 2A: the single canonical request shape for
 * {@code POST /api/v1/admin/deposits/{userId}/adjust}. No actor field - the acting admin is
 * always derived from the authenticated security context, never from the request body.
 */
@Schema(description = "Административная корректировка депозита пользователя")
public record AdminAdjustDepositRequest(

        @Schema(description = "Сумма корректировки (положительная — пополнение, отрицательная — списание)", example = "5000")
        @NotNull(message = "Сумма обязательна")
        BigDecimal amount,

        @Schema(description = "Структурированная причина корректировки")
        @NotNull(message = "Причина обязательна")
        AdminAdjustmentReason reason,

        @Schema(description = "Обязательная ссылка/основание для аудита корректировки", example = "PAYMENT:TX-12345")
        @NotBlank(message = "Ссылка (reference) обязательна")
        String reference,

        @Schema(description = "Ключ идемпотентности", example = "c2f1e6d0-...")
        @NotBlank(message = "Ключ идемпотентности обязателен")
        String idempotencyKey
) {
}
