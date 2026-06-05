package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Slf4j
@Component
public class AuctionMapper {

    public JpaAuctionEntity toEntity(Auction domain) {
        if (domain == null) return null;

        JpaAuctionEntity entity = new JpaAuctionEntity();
        entity.setId(domain.getId());
        entity.setVehicleId(domain.getVehicleId());
        entity.setVehicleInfo(domain.getVehicleInfo());
        entity.setStartingPrice(domain.getStartingPrice());
        entity.setCurrentPrice(domain.getCurrentPrice());
        entity.setReservePrice(domain.getReservePrice());
        entity.setBuyNowPrice(domain.getBuyNowPrice());
        entity.setMinBidStep(domain.getMinBidStep());
        entity.setStartTime(domain.getStartTime());
        entity.setEndTime(domain.getEndTime());
        entity.setStatus(domain.getStatus());
        entity.setWinnerId(domain.getWinnerId());
        entity.setWinningBid(domain.getWinningBid());
        entity.setTotalBids(domain.getTotalBids());
        entity.setCreatedBy(domain.getCreatedBy());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());
        entity.setExtensionCount(domain.getExtensionCount());
        entity.setMainPhotoUrl(domain.getMainPhotoUrl());
        entity.setAdditionalPhotoUrls(domain.getAdditionalPhotoUrls());
        entity.setVideoUrl(domain.getVideoUrl());
        entity.setConfirmedStart(domain.isConfirmedStart());
        entity.setConfirmationDeadline(domain.getConfirmationDeadline());
        return entity;
    }

    public Auction toDomain(JpaAuctionEntity entity) {
        if (entity == null) return null;

        // Создаём аукцион через конструктор с базовыми полями
        Auction auction = new Auction(
                entity.getVehicleId(),
                entity.getStartingPrice(),
                entity.getReservePrice(),
                entity.getBuyNowPrice(),
                entity.getMinBidStep(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getCreatedBy()
        );

        // Устанавливаем остальные поля через методы
        auction.setId(entity.getId());
        auction.setCurrentPrice(entity.getCurrentPrice());
        auction.setStatus(entity.getStatus());
        auction.setWinnerId(entity.getWinnerId());
        auction.setWinningBid(entity.getWinningBid());
        auction.setTotalBids(entity.getTotalBids());
        auction.setVehicleInfo(entity.getVehicleInfo());
        auction.setCreatedAt(entity.getCreatedAt());
        auction.setUpdatedAt(entity.getUpdatedAt());
        auction.setVersion(entity.getVersion());
        auction.setExtensionCount(entity.getExtensionCount());
        auction.setMainPhotoUrl(entity.getMainPhotoUrl());
        auction.setAdditionalPhotoUrls(entity.getAdditionalPhotoUrls());
        auction.setVideoUrl(entity.getVideoUrl());
        auction.setConfirmedStart(entity.isConfirmedStart());
        auction.setConfirmationDeadline(entity.getConfirmationDeadline());
        return auction;
    }
}
