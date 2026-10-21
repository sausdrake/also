package com.example.autoauction.auction.web.dto;

import com.example.autoauction.auction.web.validation.ValidAuctionPrices;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
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
        @Digits(integer = 8, fraction = 2, message = "Стартовая цена: не больше 99999999.99 и не больше двух знаков после запятой")
        BigDecimal startingPrice,

        @Schema(description = "Резервная цена (минимальная цена продажи)", example = "1200000")
        @DecimalMin(value = "0.01", message = "Резервная цена должна быть больше 0")
        @Digits(integer = 8, fraction = 2, message = "Резервная цена: не больше 99999999.99 и не больше двух знаков после запятой")
        BigDecimal reservePrice,

        @Schema(description = "Цена мгновенной покупки", example = "1500000")
        @DecimalMin(value = "0.01", message = "Цена мгновенной покупки должна быть больше 0")
        @Digits(integer = 8, fraction = 2, message = "Цена мгновенной покупки: не больше 99999999.99 и не больше двух знаков после запятой")
        BigDecimal buyNowPrice,
        // AUCTION-01 (BD-02): no bid-step field - the server derives it as ROUND(startingPrice x 0.01, 3)

        @Schema(description = "Время начала аукциона", example = "2026-03-10T10:00:00+07:00")
        @NotNull(message = "Время начала обязательно")
        OffsetDateTime startTime,

        @Schema(description = "Время окончания аукциона", example = "2026-03-17T10:00:00+07:00")
        @NotNull(message = "Время окончания обязательно")
        OffsetDateTime endTime,

        @Schema(description = "URL главного фото (обязательно, сценарии 5 и 17)", example = "https://...")
        @NotBlank(message = "Главное фото обязательно")
        String mainPhotoUrl,

        @Schema(description = "JSON-строка дополнительных фото", example = "[\"url1\",\"url2\"]")
        String additionalPhotoUrls
        // DEPOSIT-01 (BD-01): no deposit field - the participation deposit is the global admin setting
        // (SystemSettingsService.PARTICIPATION_DEPOSIT_KEY), snapshotted by the server at creation.
) {
}