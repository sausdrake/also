package com.example.autoauction.user.domain.port;

import com.example.autoauction.user.domain.AdminUserAction;

import java.util.List;

/** SEC-06: append-only journal of administrative actions on users - no update, no delete. */
public interface AdminUserActionRepository {

    AdminUserAction append(AdminUserAction action);

    /** The actions on one user, newest first. */
    List<AdminUserAction> findByTargetUserIdNewestFirst(Long targetUserId);
}
