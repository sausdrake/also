package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Slice 1b-1: minimal service surface - create a new (unpublished) PolicyVersion, publish one,
 * and retrieve one by id. Deliberately does NOT expose update, delete, generic CRUD, or any
 * pricing/auction-selection logic (see the Slice 1b-1 scope boundary in the pre-implementation
 * audit) - those belong to Slice 1b-2 and later.
 *
 * X3-E: extends {@link #create} with a copy-forward step (every {@link PolicyVersionViolation}
 * row from the previous published PolicyVersion is duplicated onto the new draft), adds
 * {@link #updateDraftViolation} for admin edits of a still-unpublished draft's penalty
 * configuration, and extends {@link #publish} with a completeness check - a PolicyVersion may
 * never be published with a missing or invalid violation row for any catalogue entry (locked
 * X3-E business decision: "A published PolicyVersion must NEVER have an incomplete violation
 * configuration... publication must fail closed"). Preserves every existing commission-only
 * behavior unchanged for callers that never touch violations.
 */
@Slf4j
@Service
public class PolicyVersionService {

    private final PolicyVersionRepository policyVersionRepository;
    private final ViolationDefinitionRepository violationDefinitionRepository;
    private final PolicyVersionViolationRepository policyVersionViolationRepository;

    public PolicyVersionService(PolicyVersionRepository policyVersionRepository,
                                 ViolationDefinitionRepository violationDefinitionRepository,
                                 PolicyVersionViolationRepository policyVersionViolationRepository) {
        this.policyVersionRepository = policyVersionRepository;
        this.violationDefinitionRepository = violationDefinitionRepository;
        this.policyVersionViolationRepository = policyVersionViolationRepository;
        log.info("PolicyVersionService initialized");
    }

    /**
     * X3-E: creates a new draft PolicyVersion and, in the SAME transaction, copies forward
     * every PolicyVersionViolation row from the currently published PolicyVersion (if one
     * exists) onto the new draft - identical violationDefinitionId/penaltyType/penaltyValue,
     * new rows (never shared/reused across versions). If no PolicyVersion has ever been
     * published (or the currently published one predates X3-E and has zero violation rows -
     * see V38's own migration note), the new draft simply starts with zero violation rows;
     * {@link #publish} will then fail closed until an admin adds the full catalogue.
     */
    @Transactional
    public PolicyVersion create(BigDecimal commissionRate, Long createdBy) {
        PolicyVersion policyVersion = new PolicyVersion(commissionRate, createdBy);
        PolicyVersion saved = policyVersionRepository.save(policyVersion);

        policyVersionRepository.findCurrentPublished().ifPresent(previous -> {
            List<PolicyVersionViolation> previousViolations =
                    policyVersionViolationRepository.findByPolicyVersionId(previous.getId());
            OffsetDateTime now = OffsetDateTime.now();
            for (PolicyVersionViolation previousViolation : previousViolations) {
                policyVersionViolationRepository.save(new PolicyVersionViolation(
                        saved.getId(), previousViolation.getViolationDefinitionId(),
                        previousViolation.getPenaltyType(), previousViolation.getPenaltyValue(), now));
            }
            log.info("PolicyVersion {} copied forward {} violation row(s) from previous published version {}",
                    saved.getId(), previousViolations.size(), previous.getId());
        });

        log.info("PolicyVersion {} created (versionNumber={}, commissionRate={}, createdBy={})",
                saved.getId(), saved.getVersionNumber(), commissionRate, createdBy);
        return saved;
    }

    /**
     * X3-E: admin edit of one violation's penalty configuration on a still-unpublished draft.
     * Fails closed with a clear application-level error if the target PolicyVersion is already
     * published (the DB trigger added in V38 would also reject the underlying UPDATE, but that
     * is defense-in-depth only - callers must never rely on catching a raw DB exception here).
     * Insert-or-update: if no row exists yet for this (policyVersionId, violationDefinitionId)
     * pair, one is created; otherwise the existing row is replaced (PolicyVersionViolation is
     * an immutable value - "updating" it means constructing a new instance with the same id).
     */
    @Transactional
    public PolicyVersionViolation updateDraftViolation(Long policyVersionId, Long violationDefinitionId,
                                                        PenaltyType penaltyType, BigDecimal penaltyValue) {
        PolicyVersion target = policyVersionRepository.findById(policyVersionId)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + policyVersionId));
        if (target.isPublished()) {
            throw new IllegalStateException(
                    "PolicyVersion " + policyVersionId + " уже опубликована - конфигурация нарушений неизменяема");
        }
        violationDefinitionRepository.findById(violationDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("ViolationDefinition не найден: " + violationDefinitionId));

        Long existingId = policyVersionViolationRepository
                .findByPolicyVersionIdAndViolationDefinitionId(policyVersionId, violationDefinitionId)
                .map(PolicyVersionViolation::getId)
                .orElse(null);

        PolicyVersionViolation toSave = (existingId == null)
                ? new PolicyVersionViolation(policyVersionId, violationDefinitionId, penaltyType, penaltyValue, OffsetDateTime.now())
                : new PolicyVersionViolation(existingId, policyVersionId, violationDefinitionId, penaltyType, penaltyValue, OffsetDateTime.now());

        PolicyVersionViolation saved = policyVersionViolationRepository.save(toSave);
        log.info("PolicyVersion {} violation {} set to {} {}", policyVersionId, violationDefinitionId, penaltyType, penaltyValue);
        return saved;
    }

    /**
     * X3-E: extends the existing claim-first publish with a completeness check performed
     * BEFORE the claim - every ViolationDefinition in the catalogue must have exactly one
     * valid PolicyVersionViolation row under this PolicyVersion. Fails closed (no publish, no
     * mutation) if any is missing - "do not silently publish a policy with an
     * empty/incomplete violation catalogue" (locked X3-E business decision).
     */
    @Transactional
    public PolicyVersion publish(Long id) {
        // Deliberately no separate existence check here - a non-existent id simply produces
        // an empty PolicyVersionViolation list below (same as any legitimate empty-catalogue
        // version), and claimPublish(id) still returns false for it exactly as it always has,
        // preserving the original, already-established IllegalStateException contract for
        // "not found or already published" (see PolicyVersionServiceIntegrationTest).
        List<ViolationDefinition> catalogue = violationDefinitionRepository.findAll();
        List<PolicyVersionViolation> configured = policyVersionViolationRepository.findByPolicyVersionId(id);
        Map<Long, PolicyVersionViolation> configuredByViolationId = configured.stream()
                .collect(Collectors.toMap(PolicyVersionViolation::getViolationDefinitionId, Function.identity()));

        List<String> missing = catalogue.stream()
                .filter(v -> !configuredByViolationId.containsKey(v.getId()))
                .map(ViolationDefinition::getCode)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "PolicyVersion " + id + " не может быть опубликована - отсутствует конфигурация штрафа для: " + missing);
        }

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

    /**
     * Slice 1 (Policy Administration): admin edit of a still-unpublished draft's commission
     * rate. Fails closed if the target is already published (defense-in-depth: the DB trigger
     * and {@link PolicyVersion#changeCommissionRate} would also reject it). Range/null
     * validation lives entirely in the domain - no duplicate business validation here.
     */
    @Transactional
    public PolicyVersion updateDraftCommission(Long id, BigDecimal newRate) {
        PolicyVersion target = policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + id));
        if (target.isPublished()) {
            throw new IllegalStateException(
                    "PolicyVersion " + id + " уже опубликована - ставку комиссии изменить нельзя");
        }
        target.changeCommissionRate(newRate);
        PolicyVersion saved = policyVersionRepository.save(target);
        log.info("PolicyVersion {} commissionRate updated to {}", id, newRate);
        return saved;
    }

    /**
     * Slice 1: history/listing for the admin console. {@code publishedFilter == null} returns
     * every version (drafts + published); otherwise only versions in that publication state.
     * Always newest-first by {@code versionNumber}.
     */
    @Transactional(readOnly = true)
    public List<PolicyVersion> listVersions(Boolean publishedFilter) {
        return (publishedFilter == null)
                ? policyVersionRepository.findAllOrderByVersionNumberDesc()
                : policyVersionRepository.findByPublishedOrderByVersionNumberDesc(publishedFilter);
    }

    /**
     * Slice 1: the configured violation rows for one PolicyVersion (draft or published).
     * 404-style semantics for an unknown id, consistent with {@link #getById(Long)}.
     */
    @Transactional(readOnly = true)
    public List<PolicyVersionViolation> getViolations(Long policyVersionId) {
        policyVersionRepository.findById(policyVersionId)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + policyVersionId));
        return policyVersionViolationRepository.findByPolicyVersionId(policyVersionId);
    }

    /**
     * Slice 1: the stable violation-code catalogue (read-only; seeded by V37, never mutated
     * here).
     */
    @Transactional(readOnly = true)
    public List<ViolationDefinition> listViolationDefinitions() {
        return violationDefinitionRepository.findAll();
    }
}
