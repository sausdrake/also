package com.example.autoauction.user.infrastructure.persistence;

import com.example.autoauction.user.domain.AdminUserActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** SEC-06: JPA representation of {@code admin_user_actions} (V58). */
@Entity
@Table(name = "admin_user_actions")
public class JpaAdminUserActionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private AdminUserActionType action;

    @Column(name = "details", length = 255)
    private String details;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaAdminUserActionEntity() {
    }

    public JpaAdminUserActionEntity(Long targetUserId, Long adminId, AdminUserActionType action, String details,
                                    OffsetDateTime createdAt) {
        this.targetUserId = targetUserId;
        this.adminId = adminId;
        this.action = action;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getTargetUserId() { return targetUserId; }
    public Long getAdminId() { return adminId; }
    public AdminUserActionType getAction() { return action; }
    public String getDetails() { return details; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
