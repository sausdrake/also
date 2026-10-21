package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.SaleFinalizationMethod;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Slf4j
@Entity
@Table(name = "auctions",
        uniqueConstraints = @UniqueConstraint(columnNames = "seller_diagnostic_commitment_id"))
public class JpaAuctionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "vehicle_info", length = 10000)
    private String vehicleInfo;

    @Column(name = "starting_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal startingPrice;

    @Column(name = "current_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "reserve_price", precision = 10, scale = 2)
    private BigDecimal reservePrice;

    @Column(name = "buy_now_price", precision = 10, scale = 2)
    private BigDecimal buyNowPrice;

    @Column(name = "min_bid_step", nullable = false, precision = 11, scale = 3)   // AUCTION-01 / V61
    private BigDecimal minBidStep;

    @Column(name = "required_deposit_amount", precision = 10, scale = 2)
    private BigDecimal requiredDepositAmount;

    /**
     * Slice 1b-2: nullable FK to policy_version(id). Null for legacy, pre-feature auctions.
     * updatable is intentionally NOT set to false here - the field is written exactly once via
     * the domain's own single-call-site discipline (see Auction.setPolicyVersionId's Javadoc),
     * not enforced at this layer.
     */
    @Column(name = "policy_version_id")
    private Long policyVersionId;

    /**
     * X3-C: nullable FK to seller_diagnostic_commitment(id), UNIQUE. Null only for legacy,
     * pre-X3-C auctions. updatable is intentionally NOT set to false here - the field is
     * written exactly once via the domain's own single-call-site discipline (see
     * Auction.setSellerDiagnosticCommitmentId's Javadoc), not enforced at this layer.
     */
    @Column(name = "seller_diagnostic_commitment_id")
    private Long sellerDiagnosticCommitmentId;

    /**
     * Step 2C: nullable discriminator - null only for historical auctions finalized before this
     * column existed. Written exactly once by the domain's own current-value-guarded setter.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sale_finalization_method", length = 16)
    private SaleFinalizationMethod saleFinalizationMethod;

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AuctionStatus status;

    @Column(name = "winner_id")
    private Long winnerId;

    @Column(name = "winning_bid", precision = 10, scale = 2)
    private BigDecimal winningBid;

    @Column(name = "total_bids", nullable = false)
    private Integer totalBids = 0;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "was_extended", nullable = false)
    private boolean wasExtended = false;
    @Column(name = "extension_count", nullable = false)
    private int extensionCount = 0;

    @Column(name = "main_photo_url")
    private String mainPhotoUrl;

    // JSON-строка вида ["url1","url2"] — совместима с H2 и PostgreSQL
    @Column(name = "additional_photo_urls", columnDefinition = "text")
    private String additionalPhotoUrls;

    @Column(name = "video_url")
    private String videoUrl;

    @Column(name = "confirmed_start", nullable = false)
    private boolean confirmedStart = false;

    @Column(name = "confirmation_deadline")
    private OffsetDateTime confirmationDeadline;

    @Column(name = "payment_deadline")
    private OffsetDateTime paymentDeadline;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // Конструктор по умолчанию для JPA
    protected JpaAuctionEntity() {}

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }

    public String getVehicleInfo() { return vehicleInfo; }
    public void setVehicleInfo(String vehicleInfo) { this.vehicleInfo = vehicleInfo; }

    public BigDecimal getStartingPrice() { return startingPrice; }
    public void setStartingPrice(BigDecimal startingPrice) { this.startingPrice = startingPrice; }

    public BigDecimal getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }

    public BigDecimal getReservePrice() { return reservePrice; }
    public void setReservePrice(BigDecimal reservePrice) { this.reservePrice = reservePrice; }

    public BigDecimal getBuyNowPrice() { return buyNowPrice; }
    public void setBuyNowPrice(BigDecimal buyNowPrice) { this.buyNowPrice = buyNowPrice; }

    public BigDecimal getMinBidStep() { return minBidStep; }
    public void setMinBidStep(BigDecimal minBidStep) { this.minBidStep = minBidStep; }

    public BigDecimal getRequiredDepositAmount() { return requiredDepositAmount; }
    public void setRequiredDepositAmount(BigDecimal requiredDepositAmount) { this.requiredDepositAmount = requiredDepositAmount; }

    public Long getPolicyVersionId() { return policyVersionId; }
    public void setPolicyVersionId(Long policyVersionId) { this.policyVersionId = policyVersionId; }

    public Long getSellerDiagnosticCommitmentId() { return sellerDiagnosticCommitmentId; }
    public void setSellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId) { this.sellerDiagnosticCommitmentId = sellerDiagnosticCommitmentId; }

    public SaleFinalizationMethod getSaleFinalizationMethod() { return saleFinalizationMethod; }
    public void setSaleFinalizationMethod(SaleFinalizationMethod saleFinalizationMethod) { this.saleFinalizationMethod = saleFinalizationMethod; }

    public OffsetDateTime getStartTime() { return startTime; }
    public void setStartTime(OffsetDateTime startTime) { this.startTime = startTime; }

    public OffsetDateTime getEndTime() { return endTime; }
    public void setEndTime(OffsetDateTime endTime) { this.endTime = endTime; }

    public AuctionStatus getStatus() { return status; }
    public void setStatus(AuctionStatus status) { this.status = status; }

    public Long getWinnerId() { return winnerId; }
    public void setWinnerId(Long winnerId) { this.winnerId = winnerId; }

    public BigDecimal getWinningBid() { return winningBid; }
    public void setWinningBid(BigDecimal winningBid) { this.winningBid = winningBid; }

    public Integer getTotalBids() { return totalBids; }
    public void setTotalBids(Integer totalBids) { this.totalBids = totalBids; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public boolean isWasExtended() { return wasExtended; }
    public void setWasExtended(boolean wasExtended) { this.wasExtended = wasExtended; }
    public int getExtensionCount() { return extensionCount; }
    public void setExtensionCount(int extensionCount) { this.extensionCount = extensionCount; }

    public String getMainPhotoUrl() { return mainPhotoUrl; }
    public void setMainPhotoUrl(String mainPhotoUrl) { this.mainPhotoUrl = mainPhotoUrl; }
    public String getAdditionalPhotoUrls() { return additionalPhotoUrls; }
    public void setAdditionalPhotoUrls(String additionalPhotoUrls) { this.additionalPhotoUrls = additionalPhotoUrls; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public boolean isConfirmedStart() { return confirmedStart; }
    public void setConfirmedStart(boolean confirmedStart) { this.confirmedStart = confirmedStart; }
    public OffsetDateTime getConfirmationDeadline() { return confirmationDeadline; }
    public void setConfirmationDeadline(OffsetDateTime confirmationDeadline) { this.confirmationDeadline = confirmationDeadline; }
    public OffsetDateTime getPaymentDeadline() { return paymentDeadline; }
    public void setPaymentDeadline(OffsetDateTime paymentDeadline) { this.paymentDeadline = paymentDeadline; }
}