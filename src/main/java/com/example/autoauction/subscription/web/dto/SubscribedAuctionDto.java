package com.example.autoauction.subscription.web.dto;

import com.example.autoauction.auction.domain.Auction;

import java.math.BigDecimal;

public class SubscribedAuctionDto {
    private Long auctionId;
    private String vehicleInfo;
    private BigDecimal currentPrice;
    private BigDecimal startingPrice;
    private String status;
    private String endTime;
    private String mainPhotoUrl;

    public SubscribedAuctionDto() {}

    public static SubscribedAuctionDto from(Auction a) {
        SubscribedAuctionDto dto = new SubscribedAuctionDto();
        dto.auctionId = a.getId();
        dto.vehicleInfo = a.getVehicleInfo();
        dto.currentPrice = a.getCurrentPrice();
        dto.startingPrice = a.getStartingPrice();
        dto.status = a.getStatus() != null ? a.getStatus().name() : null;
        dto.endTime = a.getEndTime() != null ? a.getEndTime().toString() : null;
        dto.mainPhotoUrl = a.getMainPhotoUrl();
        return dto;
    }

    public Long getAuctionId() { return auctionId; }
    public String getVehicleInfo() { return vehicleInfo; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public String getStatus() { return status; }
    public String getEndTime() { return endTime; }
    public String getMainPhotoUrl() { return mainPhotoUrl; }
}
