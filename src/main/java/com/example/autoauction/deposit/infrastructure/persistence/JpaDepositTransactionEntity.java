package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.DepositTransaction;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Slf4j
@Entity
@Table(name = "deposit_transactions")
public class JpaDepositTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deposit_id", nullable = false)
    private Long depositId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "auction_id")
    private Long auctionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private DepositTransaction.TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "description", length = 10000)
    private String description;

    // unique=true here only drives Hibernate's OWN ddl-auto schema generation (used by the
    // H2 test profile) so that integration tests actually exercise the same uniqueness
    // invariant that V21's raw-SQL partial unique index establishes in production (Flyway
    // is the sole schema authority there; production runs ddl-auto=validate, which does not
    // strictly check unique-constraint metadata, so this annotation has no production
    // effect). NULL handling is standard SQL behavior in both H2 and Postgres: a unique
    // index never treats two NULLs as duplicates, so this plain unique index is
    // functionally equivalent to V21's "WHERE idempotency_key IS NOT NULL" partial index
    // for the property that matters here - only non-null keys are constrained.
    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Slice 2 · Step 2A: column already added by V42 (payment infrastructure migration, nullable,
    // no FK - matches this project's existing actor/audit-id convention). Nullable for every
    // non-admin transaction type (including the new PAYMENT_CREDIT); application-enforced
    // non-null for newly created ADMIN_ADJUSTMENT rows only. No migration needed here.
    @Column(name = "performed_by_admin_id")
    private Long performedByAdminId;

    protected JpaDepositTransactionEntity() {}

    public JpaDepositTransactionEntity(Long depositId, Long userId, Long auctionId,
                                       DepositTransaction.TransactionType type,
                                       BigDecimal amount, BigDecimal balanceBefore,
                                       BigDecimal balanceAfter, String reference, String description) {
        this.depositId = depositId;
        this.userId = userId;
        this.auctionId = auctionId;
        this.type = type;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.reference = reference;
        this.description = description;
        this.createdAt = OffsetDateTime.now();
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDepositId() { return depositId; }
    public void setDepositId(Long depositId) { this.depositId = depositId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }

    public DepositTransaction.TransactionType getType() { return type; }
    public void setType(DepositTransaction.TransactionType type) { this.type = type; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public Long getPerformedByAdminId() { return performedByAdminId; }
    public void setPerformedByAdminId(Long performedByAdminId) { this.performedByAdminId = performedByAdminId; }
}