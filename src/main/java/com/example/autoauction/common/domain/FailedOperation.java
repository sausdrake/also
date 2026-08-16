// common/domain/FailedOperation.java
package com.example.autoauction.common.domain;

import java.time.OffsetDateTime;

public class FailedOperation {
    private Long id;
    private String operationType;
    private Long userId;
    private Long auctionId;
    private String errorMessage;
    private String stackTrace;
    private int retryCount;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime nextRetryAt;
    private OffsetDateTime resolvedAt;

    /** PENDING -&gt; PROCESSING -&gt; RESOLVED, or PROCESSING -&gt; PENDING (retry later) / FAILED (escalated). */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_FAILED = "FAILED";

    public FailedOperation(String operationType, Long userId, Long auctionId,
                           String errorMessage, String stackTrace, OffsetDateTime createdAt) {
        this.operationType = operationType;
        this.userId = userId;
        this.auctionId = auctionId;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.retryCount = 0;
        this.status = "PENDING";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(OffsetDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}