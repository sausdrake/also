package com.example.autoauction.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Entity
@Table(name = "users")
public class JpaUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active;

    /**
     * Slice 1A: operational controlled-beta trust flag - not legal identity verification.
     * Defaults to false (never reviewed) for every historical/newly-registered row.
     */
    @Column(nullable = false)
    private boolean trusted;

    @Column(name = "verified_by_admin_id")
    private Long verifiedByAdminId;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<JpaRoleEntity> roles = new HashSet<>();

    // Конструктор по умолчанию для JPA
    protected JpaUserEntity() {}

    // Конструктор для создания без id
    public JpaUserEntity(String username, String email, String passwordHash, boolean active) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    // Конструктор с id (для маппинга из домена)
    public JpaUserEntity(Long id, String username, String email, String passwordHash, boolean active) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isTrusted() { return trusted; }
    public void setTrusted(boolean trusted) { this.trusted = trusted; }

    public Long getVerifiedByAdminId() { return verifiedByAdminId; }
    public void setVerifiedByAdminId(Long verifiedByAdminId) { this.verifiedByAdminId = verifiedByAdminId; }

    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(OffsetDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Set<JpaRoleEntity> getRoles() { return roles; }
    public void setRoles(Set<JpaRoleEntity> roles) { this.roles = roles; }
}