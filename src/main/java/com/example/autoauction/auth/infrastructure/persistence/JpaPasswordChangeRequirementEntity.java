package com.example.autoauction.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** AUTH-01: JPA representation of {@code password_change_requirements} (V59). */
@Entity
@Table(name = "password_change_requirements")
public class JpaPasswordChangeRequirementEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "reset_by_admin_id", nullable = false)
    private Long resetByAdminId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaPasswordChangeRequirementEntity() {
    }

    public JpaPasswordChangeRequirementEntity(Long userId, Long resetByAdminId, OffsetDateTime createdAt) {
        this.userId = userId;
        this.resetByAdminId = resetByAdminId;
        this.createdAt = createdAt;
    }

    public Long getUserId() { return userId; }
    public Long getResetByAdminId() { return resetByAdminId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
