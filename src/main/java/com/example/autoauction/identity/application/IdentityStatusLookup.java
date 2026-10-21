package com.example.autoauction.identity.application;

import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * KYC-01: read-only identity status for administrative views, so the admin sees KYC and the separate
 * trusted status side by side. Users that never submitted anything have no entry (null / empty).
 */
@Service
public class IdentityStatusLookup {

    private final IdentityVerificationRepository identityVerificationRepository;

    public IdentityStatusLookup(IdentityVerificationRepository identityVerificationRepository) {
        this.identityVerificationRepository = identityVerificationRepository;
    }

    @Transactional(readOnly = true)
    public Optional<IdentityVerificationStatus> statusOf(Long userId) {
        return identityVerificationRepository.findByUserId(userId).map(IdentityVerification::getStatus);
    }

    @Transactional(readOnly = true)
    public Map<Long, IdentityVerificationStatus> statusesOf(Collection<Long> userIds) {
        return identityVerificationRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(IdentityVerification::getUserId, IdentityVerification::getStatus, (a, b) -> a));
    }
}
