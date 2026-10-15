package com.example.autoauction.auth.domain.port;

import java.time.OffsetDateTime;

/**
 * AUTH-01: users who must replace a temporary password set by an administrator before they can do
 * anything else.
 */
public interface PasswordChangeRequirementRepository {

    /** Marks the user; a repeated reset replaces the earlier mark. */
    void require(Long userId, Long resetByAdminId, OffsetDateTime at);

    boolean isRequired(Long userId);

    void clear(Long userId);
}
