package com.example.autoauction.deposit.web.dto;

import com.example.autoauction.common.web.StrictBooleanDeserializer;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Отклонение заявки на вывод")
public record RejectWithdrawalRequest(
        @Schema(description = "Причина отклонения (видна пользователю)")
        @NotBlank(message = "Причина отклонения обязательна")
        @Size(max = 1000, message = "Причина отклонения не может быть длиннее 1000 символов")
        String reason,
        @Schema(description = "Администратор подтверждает, что выплата пользователю фактически не производилась (только JSON true)")
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        @NotNull(message = "Подтвердите, что выплата пользователю не производилась")
        @AssertTrue(message = "Подтвердите, что выплата пользователю не производилась")
        Boolean payoutNotMade,
        @Schema(description = "Статус заявки, который видел администратор (PENDING или PROCESSING)")
        @NotNull(message = "Ожидаемый статус заявки обязателен")
        WithdrawalRequestStatus expectedStatus
) {}
