package com.example.autoauction.auction.web.dto;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "Ответ с данными аукциона")
public record AuctionResponse(

        @Schema(description = "ID аукциона", example = "1")
        Long id,

        @Schema(description = "ID автомобиля", example = "1")
        Long vehicleId,

        @Schema(description = "Информация об автомобиле")
        String vehicleInfo,

        @Schema(description = "Стартовая цена", example = "1000000")
        BigDecimal startingPrice,

        @Schema(description = "Текущая цена", example = "1050000")
        BigDecimal currentPrice,

        @Schema(description = "Резервная цена", example = "1200000")
        BigDecimal reservePrice,

        @Schema(description = "Цена мгновенной покупки", example = "1500000")
        BigDecimal buyNowPrice,

        @Schema(description = "Минимальный шаг ставки", example = "10000")
        BigDecimal minBidStep,

        @Schema(description = "Время начала", example = "2026-03-10T10:00:00+07:00")
        OffsetDateTime startTime,

        @Schema(description = "Время окончания", example = "2026-03-17T10:00:00+07:00")
        OffsetDateTime endTime,

        @Schema(description = "Статус аукциона")
        AuctionStatus status,

        @Schema(description = "Количество ставок", example = "5")
        Integer totalBids,

        @Schema(description = "ID победителя", example = "10")
        Long winnerId,

        @Schema(description = "Выигрышная ставка", example = "1250000")
        BigDecimal winningBid,

        @Schema(description = "Кто создал", example = "1")
        Long createdBy,

        @Schema(description = "Дата создания")
        OffsetDateTime createdAt,

        @Schema(description = "Запуск подтверждён администратором")
        boolean confirmedStart,

        @Schema(description = "URL главного фото")
        String mainPhotoUrl,

        @Schema(description = "URL дополнительных фото/видео — JSON-строка")
        String additionalPhotoUrls,

        @Schema(description = "URL видео")
        String videoUrl,

        @Schema(description = "Количество продлений аукциона")
        int extensionCount
) {
    public static AuctionResponse fromDomain(Auction auction) {
        return new AuctionResponse(
                auction.getId(),
                auction.getVehicleId(),
                auction.getVehicleInfo(),
                auction.getStartingPrice(),
                auction.getCurrentPrice(),
                auction.getReservePrice(),
                auction.getBuyNowPrice(),
                auction.getMinBidStep(),
                auction.getStartTime(),
                auction.getEndTime(),
                auction.getStatus(),
                auction.getTotalBids(),
                auction.getWinnerId(),
                auction.getWinningBid(),
                auction.getCreatedBy(),
                auction.getCreatedAt(),
                auction.isConfirmedStart(),
                auction.getMainPhotoUrl(),
                auction.getAdditionalPhotoUrls(),
                auction.getVideoUrl(),
                auction.getExtensionCount()
        );
    }
}
