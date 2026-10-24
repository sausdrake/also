package com.example.autoauction.legal.domain;

/** PRIVACY-REQ-01: OPEN -> IN_PROGRESS -> ANSWERED | REJECTED (an answer is always written). */
public enum PrivacyRequestStatus {
    OPEN,
    IN_PROGRESS,
    ANSWERED,
    REJECTED
}
