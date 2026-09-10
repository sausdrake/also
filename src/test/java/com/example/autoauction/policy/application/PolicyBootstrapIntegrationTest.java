package com.example.autoauction.policy.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity;
import com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1 (Policy Administration): proves the P0 Definition-of-Done item
 * "clean application startup produces exactly one valid published PolicyVersion #1" - <b>with no
 * manual creation of any PolicyVersion / PolicyVersionViolation / counter / published state</b>.
 *
 * <p><b>Why a test-only catalogue seeder is needed and legitimate:</b> in production, Flyway
 * migration V37 seeds the six stable {@code violation_definition} rows, and {@link PolicyBootstrap}
 * (an {@code ApplicationRunner}) then builds PolicyVersion #1 on top of that prerequisite at
 * startup. The test profile runs H2 with {@code ddl-auto=create-drop} and
 * {@code spring.flyway.enabled=false}, so V37 never executes. This class reproduces <b>only that
 * one prerequisite</b> - the six catalogue codes - via a nested {@link TestConfiguration}
 * {@code ApplicationRunner} ordered {@link Ordered#HIGHEST_PRECEDENCE} so it runs <b>before</b>
 * {@link PolicyBootstrap} (which carries no {@code @Order} and therefore runs at
 * {@code LOWEST_PRECEDENCE}). It seeds no PolicyVersion, no PolicyVersionViolation, no published
 * policy, and never touches the version counter. The PolicyVersion #1 asserted below is created
 * entirely by {@link PolicyBootstrap}'s own startup run - the tests never invoke it for the
 * clean-startup proof.
 *
 * <p>The nested {@code @TestConfiguration} gives this class its own application context (distinct
 * cache key), so the seeded catalogue and the bootstrapped policy do NOT leak into any other
 * {@code @SpringBootTest}'s shared context (where the catalogue stays empty and
 * {@link PolicyBootstrap} is a verified no-op).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PolicyBootstrap — Slice 1 clean-startup + idempotency + downstream")
class PolicyBootstrapIntegrationTest {

    static final List<String> REQUIRED_CODES = List.of(
            "SELLER_REFUSED_HANDOVER", "SELLER_ADDITIONAL_PAYMENT_DEMAND", "SELLER_HANDOVER_TERMS_CHANGED",
            "SELLER_UNREACHABLE", "VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC", "SELLER_WITHDREW_AFTER_WIN");

    /**
     * Test-only equivalent of production migration V37: seeds ONLY the six stable
     * {@code violation_definition} rows, and does so <b>before</b> {@link PolicyBootstrap} runs.
     * Creates no policy state of any kind (no PolicyVersion, no PolicyVersionViolation, no
     * counter row, nothing published).
     */
    @TestConfiguration
    static class V37EquivalentCatalogueSeeder {
        @Bean
        ApplicationRunner seedStableViolationCatalogue(SpringDataViolationDefinitionJpaRepository repo) {
            return new CatalogueSeederRunner(repo);
        }
    }

    /**
     * A concrete {@link Ordered} {@link ApplicationRunner} - not a lambda - so that
     * {@code SpringApplication.callRunners()} (which sorts runner <i>instances</i> with
     * {@code AnnotationAwareOrderComparator}) actually honours the precedence. Runs at
     * {@link Ordered#HIGHEST_PRECEDENCE}, i.e. strictly before {@link PolicyBootstrap} (no
     * {@code @Order} =&gt; {@link Ordered#LOWEST_PRECEDENCE}).
     */
    static class CatalogueSeederRunner implements ApplicationRunner, Ordered {
        private final SpringDataViolationDefinitionJpaRepository repo;

        CatalogueSeederRunner(SpringDataViolationDefinitionJpaRepository repo) {
            this.repo = repo;
        }

        @Override
        public void run(ApplicationArguments args) {
            Set<String> present = repo.findAll().stream()
                    .map(JpaViolationDefinitionEntity::getCode).collect(Collectors.toSet());
            for (String code : REQUIRED_CODES) {
                if (!present.contains(code)) {
                    repo.save(new JpaViolationDefinitionEntity(
                            code, "V37-equivalent stable catalogue (test profile only)", OffsetDateTime.now()));
                }
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    @Autowired private PolicyBootstrap policyBootstrap;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private PolicyVersionRepository policyVersionRepository;
    @Autowired private PolicyVersionViolationRepository policyVersionViolationRepository;
    @Autowired private DepositService depositService;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;

    @Test
    @DisplayName("clean startup -> PolicyBootstrap alone produced exactly one published PolicyVersion #1 with the locked seed")
    void cleanStartup_ProducesExactlyOnePublishedPolicyVersionOne() {
        // No bootstrap invocation, no policy creation here. PolicyBootstrap ran once during
        // context init, AFTER the V37-equivalent catalogue seeder.
        List<PolicyVersion> published = policyVersionRepository.findByPublishedOrderByVersionNumberDesc(true);
        assertThat(published).hasSize(1);

        PolicyVersion v1 = published.get(0);
        assertThat(v1.isPublished()).isTrue();
        assertThat(v1.getCommissionRate()).isEqualByComparingTo("0.0200");
        assertThat(v1.getCreatedBy()).isEqualTo(PolicyVersion.SYSTEM_ACTOR_ID);
        assertThat(PolicyVersion.SYSTEM_ACTOR_ID).isEqualTo(0L);

        Map<Long, String> codeById = policyVersionService.listViolationDefinitions().stream()
                .collect(Collectors.toMap(vd -> vd.getId(), vd -> vd.getCode()));
        Map<String, PolicyVersionViolation> byCode = policyVersionViolationRepository
                .findByPolicyVersionId(v1.getId()).stream()
                .collect(Collectors.toMap(v -> codeById.get(v.getViolationDefinitionId()), v -> v));

        assertThat(byCode.keySet()).containsExactlyInAnyOrderElementsOf(REQUIRED_CODES);
        assertPenalty(byCode, "SELLER_REFUSED_HANDOVER", "100");
        assertPenalty(byCode, "SELLER_ADDITIONAL_PAYMENT_DEMAND", "100");
        assertPenalty(byCode, "SELLER_HANDOVER_TERMS_CHANGED", "50");
        assertPenalty(byCode, "SELLER_UNREACHABLE", "100");
        assertPenalty(byCode, "VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC", "100");
        assertPenalty(byCode, "SELLER_WITHDREW_AFTER_WIN", "100");
    }

    @Test
    @DisplayName("bootstrap is idempotent - re-running publishes nothing new")
    void bootstrap_IsIdempotent() {
        long publishedBefore = policyVersionRepository.findByPublishedOrderByVersionNumberDesc(true).size();
        assertThat(publishedBefore).isEqualTo(1);

        boolean seededAgain = policyBootstrap.bootstrapIfNeeded();

        assertThat(seededAgain).isFalse();
        assertThat(policyVersionRepository.findByPublishedOrderByVersionNumberDesc(true).size())
                .isEqualTo(publishedBefore);
    }

    @Test
    @DisplayName("after bootstrap: getCurrentPublished + a real seller-commitment freeze both succeed with no manual PolicyVersion insert")
    void bootstrap_UnblocksDownstream() {
        // The exact call AdminAuctionService.createAuction and freezeCommitment both fail-close on:
        PolicyVersion current = policyVersionService.getCurrentPublished();
        assertThat(current).isNotNull();
        assertThat(current.isPublished()).isTrue();

        // A genuine seller diagnostic commitment freeze - internally snapshots
        // PolicyVersionService.getCurrentPublished().getId():
        Long sellerId = 900_000L + (System.nanoTime() % 90_000L);
        depositService.createDeposit(sellerId, new BigDecimal("50000"));

        DiagnosticRequest request = new DiagnosticRequest();
        request.setUserId(sellerId);
        request.setBrand("BMW");
        request.setModel("X5");
        request.setYear(2022);
        request.setVin("PB" + (System.nanoTime() % 100_000_000L));
        request.setDepositFrozen(new BigDecimal("10000"));
        request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
        request.setCreatedAt(OffsetDateTime.now());
        request.setUpdatedAt(OffsetDateTime.now());
        Long requestId = diagnosticRequestRepository.save(request).getId();

        sellerDiagnosticCommitmentService.freezeCommitment(
                sellerId, requestId, new BigDecimal("10000"), new BigDecimal("4000"));

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(requestId).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FROZEN);
        assertThat(commitment.getPolicyVersionId()).isEqualTo(current.getId());
    }

    private static void assertPenalty(Map<String, PolicyVersionViolation> byCode, String code, String expected) {
        assertThat(byCode.get(code).getPenaltyType()).isEqualTo(PenaltyType.PERCENTAGE);
        assertThat(byCode.get(code).getPenaltyValue()).isEqualByComparingTo(expected);
    }
}
