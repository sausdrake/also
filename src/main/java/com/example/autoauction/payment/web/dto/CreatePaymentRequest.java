package com.example.autoauction.payment.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * P1.5: deliberately carries ONLY {@code amount} and an optional {@code idempotencyKey} - never
 * {@code userId}, {@code currency}, {@code merchantReference}, {@code status}, {@code expiresAt}
 * or {@code providerReference}. Those are either server-derived (userId from the authenticated
 * principal, never this body) or server-owned outright (currency is fixed KGS; every other field
 * is assigned only by the server during creation/provider initiation).
 */
@Schema(description = "Запрос на создание платежа (пополнение депозита)")
public record CreatePaymentRequest(

        @Schema(description = "Запрашиваемая сумма пополнения, KGS", example = "50000", required = true)
        @NotNull(message = "Сумма обязательна")
        @DecimalMin(value = "0.01", message = "Сумма должна быть больше 0")
        @Digits(integer = 17, fraction = 2, message = "Не больше двух знаков после запятой")
        BigDecimal amount,

        @Schema(description = "Необязательный ключ идемпотентности: клиент может сгенерировать его "
                + "один раз на попытку создания и переиспользовать при повторной отправке того же "
                + "запроса (например, после сетевой ошибки), чтобы повтор не создал второй платёж",
                required = false)
        String idempotencyKey
) {
}
