package com.example.autoauction.admin.web.dto;

import com.example.autoauction.diagnostic.domain.RediagnosticReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** MVP-005: the machine-readable reason is mandatory; the comment is optional free text. */
public record RediagnosticOrderRequest(
        @NotNull(message = "Причина повторной диагностики обязательна")
        RediagnosticReason reason,

        @Size(max = 2000, message = "Комментарий не длиннее 2000 символов")
        String comment
) {
}
