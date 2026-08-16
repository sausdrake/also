package com.example.autoauction.auction.web.dto;

import com.example.autoauction.auction.web.validation.ValidAuctionPrices;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Slf4j
@ValidAuctionPrices // Добавляем кастомную аннотацию для валидации цен
@Schema(description = "Запрос на создание аукциона")
public record AuctionCreateRequest(

        @Schema(description = "ID автомобиля", example = "1")
        @NotNull(message = "ID автомобиля обязателен")
        Long vehicleId,

        @Schema(description = "Стартовая цена", example = "1000000")
        @NotNull(message = "Стартовая цена обязательна")
        @DecimalMin(value = "0.01", message = "Стартовая цена должна быть больше 0")
        BigDecimal startingPrice,

        @Schema(description = "Резервная цена (минимальная цена продажи)", example = "1200000")
        @DecimalMin(value = "0.01", message = "Резервная цена должна быть больше 0")
        BigDecimal reservePrice,

        @Schema(description = "Цена мгновенной покупки", example = "1500000")
        @DecimalMin(value = "0.01", message = "Цена мгновенной покупки должна быть больше 0")
        BigDecimal buyNowPrice,

        @Schema(description = "Минимальный шаг ставки", example = "10000")
        @NotNull(message = "Шаг ставки обязателен")
        @DecimalMin(value = "0.01", message = "Шаг ставки должен быть больше 0")
        BigDecimal minBidStep,

        @Schema(description = "Время начала аукциона", example = "2026-03-10T10:00:00+07:00")
        @NotNull(message = "Время начала обязательно")
        OffsetDateTime startTime,

        @Schema(description = "Время окончания аукциона", example = "2026-03-17T10:00:00+07:00")
        @NotNull(message = "Время окончания обязательно")
        OffsetDateTime endTime,

        @Schema(description = "URL главного фото", example = "https://...")
        String mainPhotoUrl,

        @Schema(description = "JSON-строка дополнительных фото", example = "[\"url1\",\"url2\"]")
        String additionalPhotoUrls,

        @Schema(description = "Требуемая фиксированная сумма депозита для участия в этом аукционе " +
                "(задаётся администратором индивидуально для лота, НЕ процент от стартовой цены)",
                example = "10000")
        @DecimalMin(value = "0.01", message = "Требуемый депозит должен быть больше 0")
        BigDecimal requiredDepositAmount
) {
    /**
     * Обратно-совместимый конструктор без requiredDepositAmount — для существующих
     * вызывающих кодов/тестов, которые ещё не знают об этом поле. requiredDepositAmount
     * остаётся null (см. AuctionMapper/Auction.setRequiredDepositAmount).
     */
    public AuctionCreateRequest(Long vehicleId, BigDecimal startingPrice, BigDecimal reservePrice,
                                 BigDecimal buyNowPrice, BigDecimal minBidStep,
                                 OffsetDateTime startTime, OffsetDateTime endTime,
                                 String mainPhotoUrl, String additionalPhotoUrls) {
        this(vehicleId, startingPrice, reservePrice, buyNowPrice, minBidStep,
                startTime, endTime, mainPhotoUrl, additionalPhotoUrls, null);
    }
}