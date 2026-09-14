package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Slice 1b-1: minimal service surface - create a new (unpublished) PolicyVersion, publish one,
 * and retrieve one by id.
 *
 * X3-E: extends {@link #create} with a copy-forward step (every {@link PolicyVersionViolation}
 * row from the previous published PolicyVersion is duplicated onto the new draft), adds
 * {@link #updateDraftViolation} for admin edits of a still-unpublished draft's penalty
 * configuration, and extends {@link #publish} with a completeness check.
 *
 * <p>Policy Administration concurrency lock (locked design): implements Decisions 1-6 -
 * maker/checker, publication metadata, entity-level optimistic locking (Boundary A), immutable
 * draft lineage (Decision 5), and aggregate review-to-publish freshness (Decision 6, Boundary
 * C). See {@code PolicyConflictException} and its concrete subtypes for the distinct,
 * structured conflicts this introduces, and the port interface's Javadoc
 * ({@link PolicyVersionRepository#claimPublish}, {@link PolicyVersionRepository#lockPublicationSerializationPoint})
 * for the exact concurrency mechanism each one closes.
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
     * exists) onto the new draft. Policy Administration Decision 5: captures
     * {@code basedOnPublishedVersionId} from the SAME single read of "current published" used
     * for the copy-forward step (not a second, separate read) - otherwise a publish committing
     * between two separate reads could make the declared lineage and the actually-copied
     * configuration inconsistent with each other.
     */
    @Transactional
    public PolicyVersion create(BigDecimal commissionRate, Long createdBy) {
        Optional<PolicyVersion> currentPublished = policyVersionRepository.findCurrentPublished();
        Long basedOnPublishedVersionId = currentPublished.map(PolicyVersion::getId).orElse(null);

        PolicyVersion policyVersion = new PolicyVersion(commissionRate, createdBy, basedOnPublishedVersionId);
        PolicyVersion saved = policyVersionRepository.save(policyVersion);

        currentPublished.ifPresent(previous -> {
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

        log.info("PolicyVersion {} created (versionNumber={}, commissionRate={}, createdBy={}, basedOnPublishedVersionId={})",
                saved.getId(), saved.getVersionNumber(), commissionRate, createdBy, basedOnPublishedVersionId);
        return saved;
    }

    /**
     * X3-E: admin edit of one violation's penalty configuration on a still-unpublished draft.
     * Insert-or-update: if no row exists yet for this (policyVersionId, violationDefinitionId)
     * pair, one is inserted (locked concurrency design, section C - relies on the
     * {@code uk_policy_version_violation} unique constraint as the real concurrency primitive,
     * never on the caller-supplied {@code observedVersion}, which is meaningless for a
     * not-yet-existing row); otherwise the existing row is optimistic-lock-checked against
     * {@code observedVersion} (Boundary A) and replaced. Either branch atomically increments the
     * parent's {@code configurationRevision} (Boundary C) in the SAME transaction as its own
     * mutation.
     */
    @Transactional
    public PolicyVersionViolation updateDraftViolation(Long policyVersionId, Long violationDefinitionId,
                                                        PenaltyType penaltyType, BigDecimal penaltyValue,
                                                        Long observedVersion) {
        PolicyVersion target = policyVersionRepository.findById(policyVersionId)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + policyVersionId));
        if (target.isPublished()) {
            throw new IllegalStateException(
                    "PolicyVersion " + policyVersionId + " уже опубликована - конфигурация нарушений неизменяема");
        }
        violationDefinitionRepository.findById(violationDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("ViolationDefinition не найден: " + violationDefinitionId));

        Optional<PolicyVersionViolation> existingOpt = policyVersionViolationRepository
                .findByPolicyVersionIdAndViolationDefinitionId(policyVersionId, violationDefinitionId);

        PolicyVersionViolation saved;
        if (existingOpt.isPresent()) {
            PolicyVersionViolation existing = existingOpt.get();
            if (!existing.getVersion().equals(observedVersion)) {
                throw new OptimisticVersionConflictException(
                        "Конфигурация нарушения " + violationDefinitionId, observedVersion, existing.getVersion());
            }
            PolicyVersionViolation toSave = new PolicyVersionViolation(existing.getId(), policyVersionId,
                    violationDefinitionId, penaltyType, penaltyValue, OffsetDateTime.now(), existing.getVersion());
            saved = policyVersionViolationRepository.save(toSave);
        } else {
            PolicyVersionViolation toSave = new PolicyVersionViolation(
                    policyVersionId, violationDefinitionId, penaltyType, penaltyValue, OffsetDateTime.now());
            try {
                saved = policyVersionViolationRepository.save(toSave);
            } catch (DataIntegrityViolationException e) {
                throw new ViolationInsertConflictException(policyVersionId, violationDefinitionId, e);
            }
        }

        policyVersionRepository.incrementConfigurationRevision(policyVersionId);
        log.info("PolicyVersion {} violation {} set to {} {}", policyVersionId, violationDefinitionId, penaltyType, penaltyValue);
        return saved;
    }

    /**
     * Policy Administration - the full, locked publish flow. Order (matches the locked spec's
     * explicit numbered "PUBLICATION" sequence exactly):
     * <ol>
     *   <li>load draft (404 if missing - via {@link #getById} at the controller, not here);</li>
     *   <li>maker/checker: creator cannot publish own draft;</li>
     *   <li>already-published short-circuit (existing semantics, preserved);</li>
     *   <li>completeness check (existing semantics, preserved - proven monotonic/race-free,
     *       see the locked concurrency correction's section A/F, so it is safe here, before the
     *       lock, exactly as it always has been);</li>
     *   <li>acquire the publication-serialization lock;</li>
     *   <li>re-read current published policy WHILE HOLDING the lock;</li>
     *   <li>validate lineage (Decision 5) - NULL-safe via {@link Objects#equals}, which already
     *       correctly allows the one legitimate null==null case (the very first policy ever,
     *       published when nothing else existed);</li>
     *   <li>validate configurationRevision (Decision 6) as a clear, specific pre-check;</li>
     *   <li>atomic claim - the SAME configurationRevision condition is embedded in the claim's
     *       own WHERE clause (not just the pre-check above), which is what actually closes the
     *       edit-vs-publish race for the narrow window between steps 8 and 9.</li>
     * </ol>
     */
    @Transactional
    public PolicyVersion publish(Long id, Long callerAdminId, Long observedConfigurationRevision) {
        PolicyVersion draft = policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + id));

        if (Objects.equals(draft.getCreatedBy(), callerAdminId)) {
            throw new MakerCheckerViolationException(id, callerAdminId);
        }
        if (draft.isPublished()) {
            throw new IllegalStateException(
                    "PolicyVersion " + id + " не может быть опубликована (не найдена или уже опубликована)");
        }

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

        policyVersionRepository.lockPublicationSerializationPoint();

        Long currentPublishedId = policyVersionRepository.findCurrentPublished()
                .map(PolicyVersion::getId).orElse(null);
        if (!Objects.equals(draft.getBasedOnPublishedVersionId(), currentPublishedId)) {
            throw new StalePolicyBaseException(id, draft.getBasedOnPublishedVersionId(), currentPublishedId);
        }

        if (!draft.getConfigurationRevision().equals(observedConfigurationRevision)) {
            throw new StalePolicyConfigurationException(id, observedConfigurationRevision, draft.getConfigurationRevision());
        }

        OffsetDateTime publishedAt = OffsetDateTime.now();
        boolean claimed = policyVersionRepository.claimPublish(id, callerAdminId, publishedAt, observedConfigurationRevision);
        if (!claimed) {
            PolicyVersion reloaded = policyVersionRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("PolicyVersion не найдена после попытки публикации: " + id));
            if (reloaded.isPublished()) {
                throw new IllegalStateException(
                        "PolicyVersion " + id + " не может быть опубликована (не найдена или уже опубликована)");
            }
            throw new StalePolicyConfigurationException(id, observedConfigurationRevision, reloaded.getConfigurationRevision());
        }

        PolicyVersion published = policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("PolicyVersion не найдена после публикации: " + id));
        log.info("PolicyVersion {} published (versionNumber={}, publishedBy={})",
                published.getId(), published.getVersionNumber(), published.getPublishedBy());
        return published;
    }

    @Transactional(readOnly = true)
    public PolicyVersion getById(Long id) {
        return policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + id));
    }

    /**
     * Slice 1b-2: the policy new auctions must be created under. Fails closed - no fallback to
     * a global/live setting, a hardcoded rate, or any previously-published version.
     */
    @Transactional(readOnly = true)
    public PolicyVersion getCurrentPublished() {
        return policyVersionRepository.findCurrentPublished()
                .orElseThrow(() -> new IllegalStateException(
                        "Нет опубликованной ценовой политики (PolicyVersion) - создание аукциона невозможно"));
    }

    /**
     * Slice 1 (Policy Administration): admin edit of a still-unpublished draft's commission
     * rate. Boundary A: the caller's {@code observedVersion} is checked against the real
     * persisted version BEFORE mutating, and the actual save is merge-based, so Hibernate's own
     * {@code @Version} flush-time check additionally (and independently) guards the narrower
     * race window between this check and the save. Atomically increments
     * {@code configurationRevision} (Boundary C) in the SAME transaction.
     */
    @Transactional
    public PolicyVersion updateDraftCommission(Long id, BigDecimal newRate, Long observedVersion) {
        PolicyVersion target = policyVersionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("PolicyVersion не найдена: " + id));
        if (target.isPublished()) {
            throw new IllegalStateException(
                    "PolicyVersion " + id + " уже опубликована - ставку комиссии изменить нельзя");
        }
        if (!target.getVersion().equals(observedVersion)) {
            throw new OptimisticVersionConflictException("PolicyVersion " + id, observedVersion, target.getVersion());
        }
        target.changeCommissionRate(newRate);
        PolicyVersion saved = policyVersionRepository.save(target);
        policyVersionRepository.incrementConfigurationRevision(id);
        log.info("PolicyVersion {} commissionRate updated to {}", id, newRate);
        return policyVersionRepository.findById(id).orElseThrow();
    }

    /**
     * Slice 1: history/listing for the admin console.
     */
    @Transactional(readOnly = true)
    public List<PolicyVersion> listVersions(Boolean publishedFilter) {
        return (publishedFilter == null)
                ? policyVersionRepository.findAllOrderByVersionNumberDesc()
                : policyVersionRepository.findByPublishedOrderByVersionNumberDesc(publishedFilter);
    }

    /**
     * Slice 1: the configured violation rows for one PolicyVersion (draft or published).
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
