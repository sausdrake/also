package com.example.autoauction.auction.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** AUCTION-01: what the admin auction form shows - every value is derived by the server, never entered. */
@Schema(description = "Расчёт минимального шага ставки по стартовой цене")
public record BidStepPreviewResponse(
        @Schema(description = "Стартовая цена", example = "1234567.00")
        BigDecimal startingPrice,

        @Schema(description = "Минимальный шаг: ROUND(стартовая цена x 0.01, 3)", example = "12345.670")
        BigDecimal minimumBidStep,

        @Schema(description = "Минимальная первая ставка (до тыйына): стартовая цена + шаг, округлённые вверх",
                example = "1246912.67")
        BigDecimal minimumFirstBid
) {
}
