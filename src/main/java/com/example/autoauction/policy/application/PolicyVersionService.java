package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Slice 1b-1: minimal service surface - create a new (unpublished) PolicyVersion, publish one,
 * and retrieve one by id. Deliberately does NOT expose update, delete, generic CRUD, or any
 * pricing/auction-selection logic (see the Slice 1b-1 scope boundary in the pre-implementation
 * audit) - those belong to Slice 1b-2 and later.
 */
@Slf4j
@Service
public class PolicyVersionService {

    private final PolicyVersionRepository policyVersionRepository;

    public PolicyVersionService(PolicyVersionRepository policyVersionRepository) {
        this.policyVersionRepository = policyVersionRepository;
        log.info("PolicyVersionService initialized");
    }

    @Transactional
    public PolicyVersion create(BigDecimal commissionRate, Long createdBy) {
        PolicyVersion policyVersion = new PolicyVersion(commissionRate, createdBy);
        PolicyVersion saved = policyVersionRepository.save(policyVersion);
        log.info("PolicyVersion {} created (versionNumber={}, commissionRate={}, createdBy={})",
                saved.getId(), saved.getVersionNumber(), commissionRate, createdBy);
        return saved;
    }

    @Transactional
    public PolicyVersion publish(Long id) {
        boolean claimed = policyVersionRepository.claimPublish(id);
        if (!claimed) {
            throw new IllegalStateException(
                    "PolicyVersion " + id + " не может быть опубликована (не найдена или уже опубликована)");
        }
        PolicyVersion published = policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("PolicyVersion не найдена после публикации: " + id));
        log.info("PolicyVersion {} published (versionNumber={})", published.getId(), published.getVersionNumber());
        return published;
    }

    @Transactional(readOnly = true)
    public PolicyVersion getById(Long id) {
        return policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + id));
    }

    /**
     * Slice 1b-2: the policy new auctions must be created under. Fails closed - no fallback to
     * a global/live setting, a hardcoded rate, or any previously-published version. Callers
     * (auction creation) must not catch this and substitute a default.
     */
    @Transactional(readOnly = true)
    public PolicyVersion getCurrentPublished() {
        return policyVersionRepository.findCurrentPublished()
                .orElseThrow(() -> new IllegalStateException(
                        "Нет опубликованной ценовой политики (PolicyVersion) - создание аукциона невозможно"));
    }
}
