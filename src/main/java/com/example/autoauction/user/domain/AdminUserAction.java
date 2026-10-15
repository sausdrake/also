package com.example.autoauction.user.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/** SEC-06: one journaled administrative action on a user account (append-only). */
public record AdminUserAction(Long id, Long targetUserId, Long adminId, AdminUserActionType action,
                              String details, OffsetDateTime createdAt) {

    public AdminUserAction {
        Objects.requireNonNull(targetUserId, "targetUserId не может быть null");
        Objects.requireNonNull(adminId, "adminId не может быть null");
        Objects.requireNonNull(action, "action не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
    }

    public static AdminUserAction of(Long targetUserId, Long adminId, AdminUserActionType action, String details,
                                     OffsetDateTime at) {
        return new AdminUserAction(null, targetUserId, adminId, action, details, at);
    }
}
