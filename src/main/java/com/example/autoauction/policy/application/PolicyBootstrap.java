package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Slice 1 (Policy Administration): a dedicated, idempotent startup task whose ONLY
 * responsibility is to guarantee that a valid <b>published</b> pricing policy exists, so that
 * {@code SellerDiagnosticCommitmentService.freezeCommitment} and
 * {@code AdminAuctionService.createAuction} (both fail-closed on
 * {@code PolicyVersionService.getCurrentPublished()}) can operate on a clean production
 * database with no manual SQL.
 *
 * <p>It creates and publishes PolicyVersion #1 <b>through the existing
 * {@link PolicyVersionService}</b> - never raw SQL, never bypassing {@code publish()}'s
 * completeness check, never touching the version counter directly.
 *
 * <p><b>Not a Flyway migration:</b> the test profile disables Flyway (H2, ddl-auto=create-drop),
 * so a seed migration could not be covered by an automated bootstrap acceptance test. This
 * runner executes identically in production and under {@code @SpringBootTest}.
 *
 * <p><b>Two guards, both non-throwing:</b>
 * <ol>
 *   <li>a published PolicyVersion already exists -&gt; do nothing (covers restarts, existing
 *       databases, and repeated invocations);</li>
 *   <li>the {@code violation_definition} catalogue does not yet contain every required code
 *       -&gt; do nothing and log a warning. This is the normal state under the H2 test profile
 *       (V37's seed does not run), so the auto-run is a harmless no-op for the rest of the test
 *       suite; the dedicated acceptance test seeds the catalogue and invokes
 *       {@link #bootstrapIfNeeded()} explicitly.</li>
 * </ol>
 *
 * <p><b>Atomicity:</b> the create + 6 configure + publish sequence runs inside a single
 * {@link TransactionTemplate} transaction (all inner {@code @Transactional} service methods
 * join it). A failure anywhere rolls the whole thing back - no orphan draft - so the next
 * startup retries cleanly. {@link #run(ApplicationArguments)} additionally catches any
 * exception so a bootstrap failure can never prevent application startup.
 *
 * <p><b>Concurrency:</b> the MVP runs a single backend replica. Concurrent multi-instance
 * startup could produce a harmless <i>identical</i> duplicate published version (current =
 * highest {@code versionNumber}); this is not a correctness issue and is explicitly out of
 * scope to engineer around with schema changes or distributed locks.
 */
@Slf4j
@Component
public class PolicyBootstrap implements ApplicationRunner {

    /** Seed-only value. Not a permanent business rule - admins change commission by publishing a new PolicyVersion. */
    static final BigDecimal SEED_COMMISSION_RATE = new BigDecimal("0.0200");

    /** Seed-only violation penalties, by stable code. All PERCENTAGE (0-100 scale). Order preserved for logging. */
    static final Map<String, BigDecimal> SEED_VIOLATION_PENALTIES;
    static {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("SELLER_REFUSED_HANDOVER", new BigDecimal("100"));
        m.put("SELLER_ADDITIONAL_PAYMENT_DEMAND", new BigDecimal("100"));
        m.put("SELLER_HANDOVER_TERMS_CHANGED", new BigDecimal("50"));
        m.put("SELLER_UNREACHABLE", new BigDecimal("100"));
        m.put("VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC", new BigDecimal("100"));
        m.put("SELLER_WITHDREW_AFTER_WIN", new BigDecimal("100"));
        SEED_VIOLATION_PENALTIES = Collections.unmodifiableMap(m);
    }

    private final PolicyVersionService policyVersionService;
    private final PolicyVersionRepository policyVersionRepository;
    private final ViolationDefinitionRepository violationDefinitionRepository;
    private final TransactionTemplate transactionTemplate;

    public PolicyBootstrap(PolicyVersionService policyVersionService,
                           PolicyVersionRepository policyVersionRepository,
                           ViolationDefinitionRepository violationDefinitionRepository,
                           PlatformTransactionManager transactionManager) {
        this.policyVersionService = policyVersionService;
        this.policyVersionRepository = policyVersionRepository;
        this.violationDefinitionRepository = violationDefinitionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            bootstrapIfNeeded();
        } catch (RuntimeException e) {
            log.error("PolicyBootstrap failed - NO initial pricing policy was seeded. An administrator must "
                    + "create and publish a PolicyVersion via /api/v1/admin/policy before diagnostic requests "
                    + "or auctions can proceed.", e);
        }
    }

    /**
     * @return {@code true} iff this call actually seeded and published PolicyVersion #1;
     *         {@code false} if a published PolicyVersion already existed or the catalogue was
     *         not ready. Package-visible for the bootstrap acceptance test.
     */
    boolean bootstrapIfNeeded() {
        if (policyVersionRepository.findCurrentPublished().isPresent()) {
            log.debug("PolicyBootstrap: a published PolicyVersion already exists - nothing to seed");
            return false;
        }

        Map<String, Long> idByCode = violationDefinitionRepository.findAll().stream()
                .collect(Collectors.toMap(ViolationDefinition::getCode, ViolationDefinition::getId, (a, b) -> a));
        if (!idByCode.keySet().containsAll(SEED_VIOLATION_PENALTIES.keySet())) {
            log.warn("PolicyBootstrap: violation_definition catalogue is missing one or more required codes "
                            + "(required={}, present={}) - skipping initial policy seed. Expected under the H2 test "
                            + "profile (Flyway/V37 disabled).",
                    SEED_VIOLATION_PENALTIES.keySet(), idByCode.keySet());
            return false;
        }

        // The publishing actor is deliberately a DIFFERENT reserved sentinel from the creating
        // actor (SYSTEM_ACTOR_ID) - purely so the locked maker/checker rule ("draft creator
        // cannot publish own draft") is satisfied mechanically for this one system-internal,
        // no-human-involved startup action, without fabricating a second human admin. See
        // PolicyVersion.SYSTEM_BOOTSTRAP_PUBLISHER_ID's Javadoc.
        transactionTemplate.executeWithoutResult(status -> {
            PolicyVersion draft = policyVersionService.create(SEED_COMMISSION_RATE, PolicyVersion.SYSTEM_ACTOR_ID);
            // Every seed violation is a genuine first-time configuration (no previously
            // published policy exists yet to have copied any row forward from) - the insert
            // branch is always taken, which never consults observedVersion. 0L is passed purely
            // as the documented "no row observed yet" sentinel.
            SEED_VIOLATION_PENALTIES.forEach((code, value) -> policyVersionService.updateDraftViolation(
                    draft.getId(), idByCode.get(code), PenaltyType.PERCENTAGE, value, 0L));
            PolicyVersion beforePublish = policyVersionService.getById(draft.getId());
            policyVersionService.publish(draft.getId(), PolicyVersion.SYSTEM_BOOTSTRAP_PUBLISHER_ID,
                    beforePublish.getConfigurationRevision());
        });

        log.info("PolicyBootstrap: seeded and published initial PolicyVersion (commissionRate={}, {} violations, createdBy={})",
                SEED_COMMISSION_RATE, SEED_VIOLATION_PENALTIES.size(), PolicyVersion.SYSTEM_ACTOR_ID);
        return true;
    }
}
