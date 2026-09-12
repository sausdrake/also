package com.example.autoauction.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.Set;

@Slf4j
@Schema(description = "Пользователь системы")
public class User {

    @Schema(description = "Уникальный идентификатор", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
    private final Long id;

    @Schema(description = "Имя пользователя", example = "john_doe", required = true, minLength = 3, maxLength = 50)
    private final String username;

    @Schema(description = "Email пользователя", example = "john@example.com", required = true)
    private final String email;

    @Schema(description = "Хеш пароля", example = "$2a$10$XURPShQNCsLjp1ESc2laoObo9QZDhxz73hJPaEv7/cBha4pk0AgP.",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private final String passwordHash;

    @Schema(description = "Активен ли пользователь", example = "true", defaultValue = "true")
    private final boolean active;

    @Schema(description = "Роли пользователя")
    private final Set<Role> roles;

    /**
     * Slice 1A: an administrator has explicitly decided this user is trusted to perform the
     * protected participant actions of the controlled beta (bidding, Buy Now). This is an
     * operational trust decision for the controlled beta - it is NOT legal identity verification
     * and must never be represented or reported as KYC/passport verification.
     */
    @Schema(description = "Доверен ли пользователь для участия в торгах (контролируемый бета-режим, не юридическая верификация)",
            example = "false", defaultValue = "false")
    private final boolean trusted;

    @Schema(description = "ID администратора, который в настоящий момент подтверждает доверие пользователю (null, если ещё не рассматривался)")
    private final Long verifiedByAdminId;

    @Schema(description = "Когда администратор принял текущее решение о доверии (null, если ещё не рассматривался)")
    private final OffsetDateTime verifiedAt;

    /**
     * Original constructor, unchanged - every existing call site continues to compile and now
     * means exactly what the locked business rule requires: a newly constructed User defaults to
     * untrusted, never-yet-reviewed (trusted=false, verifiedByAdminId=null, verifiedAt=null).
     */
    public User(Long id, String username, String email, String passwordHash, boolean active, Set<Role> roles) {
        this(id, username, email, passwordHash, active, roles, false, null, null);
    }

    /**
     * Slice 1A: full-state constructor, used by persistence reconstruction and by the trust/
     * untrust application operations. trusted/verifiedByAdminId/verifiedAt together represent only
     * the CURRENT trust decision - there is no historical ledger here (deliberately, per the
     * locked scope: this is not a verification-history/audit-framework feature).
     */
    public User(Long id, String username, String email, String passwordHash, boolean active, Set<Role> roles,
                 boolean trusted, Long verifiedByAdminId, OffsetDateTime verifiedAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.active = active;
        this.roles = roles;
        this.trusted = trusted;
        this.verifiedByAdminId = verifiedByAdminId;
        this.verifiedAt = verifiedAt;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public Set<Role> getRoles() { return roles; }
    public boolean isTrusted() { return trusted; }
    public Long getVerifiedByAdminId() { return verifiedByAdminId; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
}