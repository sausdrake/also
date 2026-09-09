// diagnostic/commitment/application/SellerViolationResolutionEngine.java
package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolutionItem;
import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * X3-E: the ONE shared seller-violation calculation/claim/charge engine (locked business
 * decision - "do not create two independent penalty engines"), reusable by both triggers:
 * a post-auction SALE_FAILED/C ({@link com.example.autoauction.auction.application.SaleFailureResolutionOrchestrator})
 * and the pre-auction diagnostic-rejection SELLER_VIOLATION category. Each trigger's own
 * caller resolves which {@code policyVersionId} snapshot applies to it (Auction.policyVersionId
 * for the post-auction trigger, SellerDiagnosticCommitment.policyVersionId for the diagnostic
 * trigger - locked X3-E business decision, the two are intentionally allowed to diverge) and
 * passes it in explicitly - this class never re-derives it, keeping lifecycle orchestration
 * separate from the shared calculation/claim/charge mechanism itself.
 *
 * Handles, in order: eligibility (commitment must be AWAITING_FINALIZATION, or an idempotent
 * no-op / fail-closed conflict if already FINALIZED), violation-id validation (non-empty, no
 * duplicates, every id must exist in the catalogue), the financially-inert-violation guard
 * (VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC - see the class-level Javadoc on
 * FINANCIALLY_INERT_VIOLATION_CODES), policy-configuration lookup (fail closed if any
 * violation has no PolicyVersionViolation row under the given policyVersionId), percentage/
 * fixed-amount calculation with per-violation HALF_UP/scale-2 rounding, the aggregate cap
 * (never allocated back to individual violations - each item keeps its own uncapped
 * calculatedPenaltyAmount), claim-first finalization, audit persistence, and the dedicated
 * seller-violation ledger charge.
 */
@Slf4j
@Service
public class SellerViolationResolutionEngine {

    /**
     * X3-E: violations that exist in the stable catalogue (selectable/configurable like any
     * other code) but are refused at resolution time, fail closed, regardless of policy
     * configuration - not an admin-flippable setting. Enabling VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC
     * for real requires a future evidence/re-diagnostic-comparison mechanism that does not
     * exist yet in this codebase (see the X3-E final scope report, section F) - this is a
     * code-level capability gate, not a policy decision an admin can make today.
     */
    private static final Set<String> FINANCIALLY_INERT_VIOLATION_CODES =
            Set.of("VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC");

    private final SellerDiagnosticCommitmentRepository commitmentRepository;
    private final ViolationDefinitionRepository violationDefinitionRepository;
    private final PolicyVersionViolationRepository policyVersionViolationRepository;
    private final SellerViolationResolutionRepository sellerViolationResolutionRepository;
    private final DepositService depositService;

    public SellerViolationResolutionEngine(SellerDiagnosticCommitmentRepository commitmentRepository,
                                            ViolationDefinitionRepository violationDefinitionRepository,
                                            PolicyVersionViolationRepository policyVersionViolationRepository,
                                            SellerViolationResolutionRepository sellerViolationResolutionRepository,
                                            DepositService depositService) {
        this.commitmentRepository = commitmentRepository;
        this.violationDefinitionRepository = violationDefinitionRepository;
        this.policyVersionViolationRepository = policyVersionViolationRepository;
        this.sellerViolationResolutionRepository = sellerViolationResolutionRepository;
        this.depositService = depositService;
    }

    /**
     * Resolves {@code sellerDiagnosticCommitmentId} as a seller violation (Outcome C) under
     * {@code policyVersionId}, applying every violation in {@code violationDefinitionIds}.
     *
     * Idempotency: if the commitment is already FINALIZED/C with the exact same set of
     * violation ids (order-independent), this is a safe no-op. If it is FINALIZED with any
     * other outcome, or FINALIZED/C with a DIFFERENT violation set, or in any other
     * non-AWAITING_FINALIZATION status, this fails closed with IllegalStateException and
     * mutates nothing.
     */
    @Transactional
    public void resolve(Long sellerDiagnosticCommitmentId, Long policyVersionId, List<Long> violationDefinitionIds,
                         Long resolvedBy, TriggerSource triggerSource, Long auctionId) {
        SellerDiagnosticCommitment commitment = commitmentRepository.findById(sellerDiagnosticCommitmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "SellerDiagnosticCommitment " + sellerDiagnosticCommitmentId + " не найден"));

        if (commitment.getStatus() != CommitmentStatus.AWAITING_FINALIZATION) {
            if (commitment.getStatus() == CommitmentStatus.FINALIZED
                    && commitment.getFinalizationOutcome() == FinalizationOutcome.C
                    && isSameViolationSet(sellerDiagnosticCommitmentId, violationDefinitionIds)) {
                log.info("Commitment {} уже FINALIZED как C с тем же набором нарушений - идемпотентный no-op",
                        sellerDiagnosticCommitmentId);
                return;
            }
            throw new IllegalStateException(String.format(
                    "Commitment %d не готов к финализации как C: текущий статус '%s'%s",
                    sellerDiagnosticCommitmentId, commitment.getStatus(),
                    commitment.getFinalizationOutcome() != null ? " (outcome=" + commitment.getFinalizationOutcome() + ")" : ""));
        }

        List<ViolationDefinition> resolvedViolations = validateAndResolveViolations(violationDefinitionIds);

        BigDecimal committedAmount = commitment.getCommittedAmount();
        List<SellerViolationResolutionItem> items = new ArrayList<>();
        BigDecimal rawPenalty = BigDecimal.ZERO;

        for (ViolationDefinition violation : resolvedViolations) {
            PolicyVersionViolation configured = policyVersionViolationRepository
                    .findByPolicyVersionIdAndViolationDefinitionId(policyVersionId, violation.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "PolicyVersion " + policyVersionId + " не содержит конфигурацию штрафа для нарушения '"
                                    + violation.getCode() + "' - финализация невозможна (no penalty without valid policy)"));

            BigDecimal individualPenalty = calculateIndividualPenalty(configured, committedAmount);
            rawPenalty = rawPenalty.add(individualPenalty);

            items.add(new SellerViolationResolutionItem(null, violation.getId(), configured.getPenaltyType(),
                    configured.getPenaltyValue(), individualPenalty, OffsetDateTime.now()));
        }

        // setScale(2, HALF_UP) explicitly on both results: BigDecimal.min()/subtract() do not
        // rescale their result to match the larger operand's scale, so if committedAmount
        // itself happens to carry a smaller scale than the scale-2 rawPenalty sum (e.g. a
        // whole-number amount), applied/refund could otherwise end up scale-0 instead of the
        // scale-2 the NUMERIC(19,2) columns and every other monetary value in this class use.
        BigDecimal appliedPenalty = rawPenalty.min(committedAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal refundAmount = committedAmount.subtract(appliedPenalty).setScale(2, RoundingMode.HALF_UP);
        OffsetDateTime finalizedAt = OffsetDateTime.now();

        // Claim FIRST: nothing financial happens unless this call's own UPDATE actually
        // matched the row - identical discipline to SaleCommitmentResolutionOrchestrator/
        // SellerDiagnosticCommitmentFinalizationService.
        boolean claimed = commitmentRepository.finalize(commitment.getDiagnosticRequestId(), FinalizationOutcome.C,
                appliedPenalty, refundAmount, finalizedAt);

        if (!claimed) {
            SellerDiagnosticCommitment current = commitmentRepository.findById(sellerDiagnosticCommitmentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "SellerDiagnosticCommitment " + sellerDiagnosticCommitmentId + " не найден"));

            if (current.getStatus() == CommitmentStatus.FINALIZED && current.getFinalizationOutcome() == FinalizationOutcome.C
                    && isSameViolationSet(sellerDiagnosticCommitmentId, violationDefinitionIds)) {
                log.info("Commitment {} уже FINALIZED как C (проиграна гонка claim) - идемпотентный no-op",
                        sellerDiagnosticCommitmentId);
                return;
            }

            throw new IllegalStateException(String.format(
                    "Не удалось финализировать commitment %d как C - неожиданное состояние: '%s'%s",
                    sellerDiagnosticCommitmentId, current.getStatus(),
                    current.getFinalizationOutcome() != null ? " (outcome=" + current.getFinalizationOutcome() + ")" : ""));
        }

        // Claim won - persist the audit trail in the SAME transaction as the claim.
        SellerViolationResolution resolution = new SellerViolationResolution(sellerDiagnosticCommitmentId,
                triggerSource, auctionId, policyVersionId, rawPenalty, appliedPenalty, refundAmount,
                resolvedBy, finalizedAt, finalizedAt);
        sellerViolationResolutionRepository.saveWithItems(resolution, items);

        // Ledger mutation shares this same transaction (REQUIRED, no REQUIRES_NEW): if it
        // throws, the whole transaction - including the claim and audit rows just persisted -
        // rolls back. auctionId is passed through exactly as received - real Auction id for
        // SALE_FAILED_C, null for DIAGNOSTIC_REJECTION - never the diagnosticRequestId (X3-E
        // corrective: DepositTransaction.auctionId must never be overloaded with a different
        // domain identity; see DepositService.chargeSellerViolationPenalty's own Javadoc).
        depositService.chargeSellerViolationPenalty(commitment.getDepositId(), commitment.getDiagnosticRequestId(),
                auctionId, committedAmount, appliedPenalty);

        log.info("Commitment {} finalized as C via {}: violations={}, rawPenalty={}, appliedPenalty={}, refund={}",
                sellerDiagnosticCommitmentId, triggerSource, violationDefinitionIds, rawPenalty, appliedPenalty, refundAmount);
    }

    private List<ViolationDefinition> validateAndResolveViolations(List<Long> violationDefinitionIds) {
        if (violationDefinitionIds == null || violationDefinitionIds.isEmpty()) {
            throw new IllegalArgumentException("violationDefinitionIds обязателен и не может быть пустым для Outcome C");
        }
        Set<Long> seen = new HashSet<>();
        List<ViolationDefinition> resolved = new ArrayList<>();
        for (Long id : violationDefinitionIds) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Дублирующееся нарушение в одном разрешении: " + id);
            }
            ViolationDefinition violation = violationDefinitionRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("ViolationDefinition не найден: " + id));
            if (FINANCIALLY_INERT_VIOLATION_CODES.contains(violation.getCode())) {
                throw new IllegalStateException("Нарушение '" + violation.getCode() +
                        "' пока не может быть применено финансово - отсутствует механизм подтверждения (evidence mechanism)");
            }
            resolved.add(violation);
        }
        return resolved;
    }

    private BigDecimal calculateIndividualPenalty(PolicyVersionViolation configured, BigDecimal committedAmount) {
        if (configured.getPenaltyType() == PenaltyType.PERCENTAGE) {
            return committedAmount
                    .multiply(configured.getPenaltyValue())
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        }
        return configured.getPenaltyValue().setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Idempotency comparison: the requested violation-id set (deduplicated) must exactly
     * match the set already recorded for this commitment's existing SellerViolationResolution
     * (if any) for a retry to be treated as a safe no-op rather than a conflicting attempt.
     */
    private boolean isSameViolationSet(Long sellerDiagnosticCommitmentId, List<Long> requestedViolationIds) {
        Optional<SellerViolationResolution> existing =
                sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(sellerDiagnosticCommitmentId);
        if (existing.isEmpty()) {
            return false;
        }
        Set<Long> existingIds = new LinkedHashSet<>();
        for (SellerViolationResolutionItem item : sellerViolationResolutionRepository.findItemsByResolutionId(existing.get().getId())) {
            existingIds.add(item.getViolationDefinitionId());
        }
        Set<Long> requestedIds = requestedViolationIds == null ? Set.of() : new HashSet<>(requestedViolationIds);
        return existingIds.equals(requestedIds);
    }
}
