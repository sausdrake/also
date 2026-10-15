package com.example.autoauction.deposit.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** F6.1: confirming a manual payout requires a note on how it was made - never full card or account numbers. */
@Schema(description = "Подтверждение ручной выплаты по заявке на вывод")
public record MarkWithdrawalPaidRequest(
        @Schema(description = "Как и когда произведена ручная выплата (без полных номеров карт и счетов)")
        @NotBlank(message = "Заметка о произведённой выплате обязательна")
        @Size(max = 1000, message = "Заметка о выплате не может быть длиннее 1000 символов")
        String payoutNote
) {}
