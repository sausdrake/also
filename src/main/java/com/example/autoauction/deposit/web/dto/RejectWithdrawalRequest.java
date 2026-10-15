package com.example.autoauction.deposit.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Отклонение заявки на вывод")
public record RejectWithdrawalRequest(
        @Schema(description = "Причина отклонения (видна пользователю)")
        @NotBlank(message = "Причина отклонения обязательна")
        @Size(max = 1000, message = "Причина отклонения не может быть длиннее 1000 символов")
        String reason
) {}
