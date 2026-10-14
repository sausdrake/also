package com.example.autoauction.identity.application;

import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MVP-004: the single KYC gate for user-initiated protected operations - bids, Buy Now,
 * deposit, withdrawal, payment creation and diagnostic request (selling) submission. Only a
 * current {@link IdentityVerificationStatus#VERIFIED} verification passes; no row (never
 * submitted), PENDING_REVIEW, REJECTED and REVOKED are all refused.
 *
 * <p>Called by the web adapters at the user-initiated entry points, not inside the use-case
 * services: those services are also driven by system flows (settlement, refunds, recovery
 * schedulers) that must keep working for a user whose verification was later revoked.
 *
 * <p>{@code User.trusted} is a separate beta-access mechanism and is NOT consulted here - KYC
 * and trust are independent requirements.
 */
@Service
public class VerifiedIdentityGate {

    private final IdentityVerificationRepository identityVerificationRepository;

    public VerifiedIdentityGate(IdentityVerificationRepository identityVerificationRepository) {
        this.identityVerificationRepository = identityVerificationRepository;
    }

    @Transactional(readOnly = true)
    public boolean isVerified(Long userId) {
        if (userId == null) {
            return false;
        }
        return identityVerificationRepository.findByUserId(userId)
                .map(v -> v.getStatus() == IdentityVerificationStatus.VERIFIED)
                .orElse(false);
    }

    /** @throws IdentityVerificationRequiredException unless the user's identity is VERIFIED. */
    @Transactional(readOnly = true)
    public void requireVerified(Long userId) {
        if (!isVerified(userId)) {
            throw new IdentityVerificationRequiredException();
        }
    }
}
