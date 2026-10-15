package com.example.autoauction.user.domain;

/** SEC-06: the administrative actions on a user account that are journaled. */
public enum AdminUserActionType {
    BLOCK,
    UNBLOCK,
    TRUST,
    UNTRUST,
    /** the user's roles were replaced by exactly one role */
    ROLE_SET,
    /** a role was added to the user's roles */
    ROLE_ADD,
    /** AUTH-01: the password was replaced by a one-time temporary password */
    PASSWORD_RESET
}
