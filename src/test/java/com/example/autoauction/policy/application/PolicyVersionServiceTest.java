package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice 1b-2 / Policy Administration: deterministic, mocked-repository unit tests.
 *
 * <p>Convention used throughout this file's publish() tests: the draft's {@code createdBy} is
 * {@code DRAFT_CREATOR} (1L) and the publishing caller is {@code CHECKER} (2L), a different
 * admin, satisfying the locked maker/checker rule (Decision 1) unless a test specifically
 * exercises that rule. Unless a test specifically exercises lineage/freshness, the draft's
 * {@code basedOnPublishedVersionId} is {@code null} and {@code findCurrentPublished()} is
 * stubbed to {@code Optional.empty()} (null == null matches - Decision 5), and
 * {@code configurationRevision} is {@code 0L} with the same value passed as
 * {@code observedConfigurationRevision} (Decision 6).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyVersionService — Slice 1b-2 / Policy Administration unit tests")
class PolicyVersionServiceTest {

    private static final Long DRAFT_CREATOR = 1L;
    private static final Long CHECKER = 2L;

    @Mock
    private PolicyVersionRepository policyVersionRepository;

    @Mock
    private ViolationDefinitionRepository violationDefinitionRepository;

    @Mock
    private PolicyVersionViolationRepository policyVersionViolationRepository;

    @InjectMocks
    private PolicyVersionService policyVersionService;

    private static PolicyVersion version(Long id, Long versionNumber, BigDecimal rate, boolean published,
                                          Long createdBy, Long basedOnPublishedVersionId,
                                          Long version, Long configurationRevision) {
        return new PolicyVersion(id, versionNumber, rate, published, createdBy, OffsetDateTime.now(),
                published ? CHECKER : null, published ? OffsetDateTime.now() : null,
                basedOnPublishedVersionId, version, configurationRevision);
    }

    @Test
    @DisplayName("getCurrentPublished() throws IllegalStateException when no PolicyVersion is published (fail closed)")
    void getCurrentPublished_WhenNonePublished_ShouldThrowIllegalStateException() {
        when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> policyVersionService.getCurrentPublished());
    }

    @Test
    @DisplayName("getCurrentPublished() returns exactly what the repository resolves as current")
    void getCurrentPublished_WhenOnePublished_ShouldReturnIt() {
        PolicyVersion published = version(5L, 3L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
        when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.of(published));

        PolicyVersion result = policyVersionService.getCurrentPublished();

        assertEquals(5L, result.getId());
        assertEquals(3L, result.getVersionNumber());
        assertEquals(0, new BigDecimal("0.02").compareTo(result.getCommissionRate()));
    }

    @Nested
    @DisplayName("X3-E: create() copy-forward")
    class CreateCopyForwardTests {

        @Test
        @DisplayName("No previously published PolicyVersion -> new draft gets zero violation rows, no copy attempted")
        void create_NoPreviousPublished_NoCopyForward() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());
            when(policyVersionRepository.save(any())).thenReturn(draft);

            PolicyVersion result = policyVersionService.create(new BigDecimal("0.02"), DRAFT_CREATOR);

            assertEquals(10L, result.getId());
            verify(policyVersionViolationRepository, never()).findByPolicyVersionId(any());
            verify(policyVersionViolationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Previously published PolicyVersion exists -> every one of its violation rows is copied to the new draft")
        void create_PreviousPublishedExists_CopiesForwardEveryViolationRow() {
            PolicyVersion previous = version(9L, 4L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 3L);
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.03"), false, DRAFT_CREATOR, 9L, 0L, 0L);
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.of(previous));
            when(policyVersionRepository.save(any())).thenReturn(draft);
            List<PolicyVersionViolation> previousViolations = List.of(
                    new PolicyVersionViolation(100L, 9L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now()),
                    new PolicyVersionViolation(101L, 9L, 2L, PenaltyType.PERCENTAGE, new BigDecimal("50"), OffsetDateTime.now()));
            when(policyVersionViolationRepository.findByPolicyVersionId(9L)).thenReturn(previousViolations);
            when(policyVersionViolationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            policyVersionService.create(new BigDecimal("0.03"), DRAFT_CREATOR);

            ArgumentCaptor<PolicyVersionViolation> captor = ArgumentCaptor.forClass(PolicyVersionViolation.class);
            verify(policyVersionViolationRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(PolicyVersionViolation::getPolicyVersionId)
                    .containsOnly(10L);
            assertThat(captor.getAllValues())
                    .extracting(PolicyVersionViolation::getViolationDefinitionId)
                    .containsExactlyInAnyOrder(1L, 2L);
        }
    }

    @Nested
    @DisplayName("Policy Administration: publish() - maker/checker, lineage, freshness, completeness")
    class PublishTests {

        @Test
        @DisplayName("Every catalogue violation configured, different checker, fresh lineage/revision -> publish succeeds")
        void publish_AllConditionsSatisfied_Succeeds() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion publishedNow = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft), Optional.of(publishedNow));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of(
                    new ViolationDefinition(1L, "A", "d", OffsetDateTime.now()),
                    new ViolationDefinition(2L, "B", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of(
                    new PolicyVersionViolation(100L, 10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now()),
                    new PolicyVersionViolation(101L, 10L, 2L, PenaltyType.PERCENTAGE, new BigDecimal("50"), OffsetDateTime.now())));
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());
            when(policyVersionRepository.claimPublish(eq(10L), eq(CHECKER), any(), eq(0L))).thenReturn(true);

            PolicyVersion result = policyVersionService.publish(10L, CHECKER, 0L);

            assertThat(result.isPublished()).isTrue();
        }

        @Test
        @DisplayName("Creator attempting to publish own draft -> MakerCheckerViolationException, no claim attempted")
        void publish_CreatorIsCaller_RejectsMakerChecker() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> policyVersionService.publish(10L, DRAFT_CREATOR, 0L))
                    .isInstanceOf(MakerCheckerViolationException.class);

            verify(policyVersionRepository, never()).lockPublicationSerializationPoint();
            verify(policyVersionRepository, never()).claimPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Already-published draft -> IllegalStateException, no claim attempted")
        void publish_AlreadyPublished_Rejects() {
            PolicyVersion published = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(published));

            assertThrows(IllegalStateException.class, () -> policyVersionService.publish(10L, CHECKER, 0L));
            verify(policyVersionRepository, never()).claimPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Missing violation configuration -> fails closed before locking/claiming, no mutation")
        void publish_IncompleteConfiguration_FailsClosedBeforeClaim() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of(
                    new ViolationDefinition(1L, "SELLER_REFUSED_HANDOVER", "d", OffsetDateTime.now()),
                    new ViolationDefinition(2L, "SELLER_UNREACHABLE", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of(
                    new PolicyVersionViolation(100L, 10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now())));
            // violation id 2 (SELLER_UNREACHABLE) has no configured row.

            assertThatThrownBy(() -> policyVersionService.publish(10L, CHECKER, 0L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SELLER_UNREACHABLE");

            verify(policyVersionRepository, never()).lockPublicationSerializationPoint();
            verify(policyVersionRepository, never()).claimPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Empty catalogue (no ViolationDefinition rows at all) -> publish trivially succeeds")
        void publish_EmptyCatalogue_TriviallySucceeds() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion publishedNow = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft), Optional.of(publishedNow));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());
            when(policyVersionRepository.claimPublish(eq(10L), eq(CHECKER), any(), eq(0L))).thenReturn(true);

            PolicyVersion result = policyVersionService.publish(10L, CHECKER, 0L);

            assertThat(result.isPublished()).isTrue();
        }

        @Test
        @DisplayName("Stale lineage (draft's base != current published id) -> StalePolicyBaseException, no claim attempted")
        void publish_StaleLineage_Rejects() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, 1L, 0L, 0L);
            PolicyVersion currentPublished = version(2L, 2L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.of(currentPublished));

            assertThatThrownBy(() -> policyVersionService.publish(10L, CHECKER, 0L))
                    .isInstanceOf(StalePolicyBaseException.class);

            verify(policyVersionRepository, never()).claimPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Draft with no base (null) is publishable when nothing has ever been published " +
                "(null == null lineage match)")
        void publish_NullLineage_MatchesWhenNothingPublishedYet() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion publishedNow = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft), Optional.of(publishedNow));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());
            when(policyVersionRepository.claimPublish(eq(10L), eq(CHECKER), any(), eq(0L))).thenReturn(true);

            PolicyVersion result = policyVersionService.publish(10L, CHECKER, 0L);

            assertThat(result.isPublished()).isTrue();
        }

        @Test
        @DisplayName("Pre-existing historical draft with NULL lineage, but a policy IS currently published " +
                "-> fails closed (NULL lineage never matches a real current id)")
        void publish_NullLineageButSomethingPublished_FailsClosed() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion currentPublished = version(2L, 2L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.of(currentPublished));

            assertThatThrownBy(() -> policyVersionService.publish(10L, CHECKER, 0L))
                    .isInstanceOf(StalePolicyBaseException.class);
        }

        @Test
        @DisplayName("Stale configuration revision (pre-check) -> StalePolicyConfigurationException, no claim attempted")
        void publish_StaleConfigurationRevision_Rejects() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 3L, 5L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());

            assertThatThrownBy(() -> policyVersionService.publish(10L, CHECKER, 3L))
                    .isInstanceOf(StalePolicyConfigurationException.class);

            verify(policyVersionRepository, never()).claimPublish(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Atomic claim loses the race (configurationRevision changed between pre-check and claim) " +
                "-> reload shows not-yet-published -> StalePolicyConfigurationException")
        void publish_ClaimFailsAfterPrecheckPassed_StaleConfiguration() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion reloadedAfterRace = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 1L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft), Optional.of(reloadedAfterRace));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());
            when(policyVersionRepository.claimPublish(eq(10L), eq(CHECKER), any(), eq(0L))).thenReturn(false);

            assertThatThrownBy(() -> policyVersionService.publish(10L, CHECKER, 0L))
                    .isInstanceOf(StalePolicyConfigurationException.class);
        }

        @Test
        @DisplayName("Atomic claim loses the race because a second concurrent publish already won " +
                "-> reload shows published -> IllegalStateException")
        void publish_ClaimFailsBecauseAlreadyPublishedByRacingCall_IllegalState() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion reloadedAfterRace = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft), Optional.of(reloadedAfterRace));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());
            when(policyVersionRepository.claimPublish(eq(10L), eq(CHECKER), any(), eq(0L))).thenReturn(false);

            assertThrows(IllegalStateException.class, () -> policyVersionService.publish(10L, CHECKER, 0L));
        }
    }

    @Nested
    @DisplayName("X3-E: updateDraftViolation()")
    class UpdateDraftViolationTests {

        @Test
        @DisplayName("Target PolicyVersion already published -> IllegalStateException, no mutation")
        void updateDraftViolation_AlreadyPublished_Rejects() {
            PolicyVersion published = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(published));

            assertThatThrownBy(() -> policyVersionService.updateDraftViolation(10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50"), 0L))
                    .isInstanceOf(IllegalStateException.class);

            verify(policyVersionViolationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Unpublished draft, no existing row for this violation -> inserts a new one, " +
                "observedVersion ignored, parent revision incremented")
        void updateDraftViolation_DraftNoExistingRow_Inserts() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findById(1L))
                    .thenReturn(Optional.of(new ViolationDefinition(1L, "A", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(10L, 1L))
                    .thenReturn(Optional.empty());
            when(policyVersionViolationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            policyVersionService.updateDraftViolation(10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50"), 0L);

            ArgumentCaptor<PolicyVersionViolation> captor = ArgumentCaptor.forClass(PolicyVersionViolation.class);
            verify(policyVersionViolationRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isNull();
            verify(policyVersionRepository).incrementConfigurationRevision(10L);
        }

        @Test
        @DisplayName("Unpublished draft, existing row with matching observedVersion -> replaces it (same id), " +
                "parent revision incremented")
        void updateDraftViolation_DraftExistingRowMatchingVersion_ReplacesWithSameId() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findById(1L))
                    .thenReturn(Optional.of(new ViolationDefinition(1L, "A", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(10L, 1L))
                    .thenReturn(Optional.of(new PolicyVersionViolation(200L, 10L, 1L, PenaltyType.PERCENTAGE,
                            new BigDecimal("100"), OffsetDateTime.now(), 0L)));
            when(policyVersionViolationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            policyVersionService.updateDraftViolation(10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50"), 0L);

            ArgumentCaptor<PolicyVersionViolation> captor = ArgumentCaptor.forClass(PolicyVersionViolation.class);
            verify(policyVersionViolationRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(200L);
            assertThat(captor.getValue().getPenaltyValue()).isEqualByComparingTo("50");
            verify(policyVersionRepository).incrementConfigurationRevision(10L);
        }

        @Test
        @DisplayName("Unpublished draft, existing row with STALE observedVersion -> OptimisticVersionConflictException, no mutation")
        void updateDraftViolation_DraftExistingRowStaleVersion_Rejects() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findById(1L))
                    .thenReturn(Optional.of(new ViolationDefinition(1L, "A", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(10L, 1L))
                    .thenReturn(Optional.of(new PolicyVersionViolation(200L, 10L, 1L, PenaltyType.PERCENTAGE,
                            new BigDecimal("100"), OffsetDateTime.now(), 2L)));

            assertThatThrownBy(() -> policyVersionService.updateDraftViolation(
                    10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50"), 0L))
                    .isInstanceOf(OptimisticVersionConflictException.class);

            verify(policyVersionViolationRepository, never()).save(any());
            verify(policyVersionRepository, never()).incrementConfigurationRevision(any());
        }
    }

    @Nested
    @DisplayName("Slice 1 (Policy Administration): updateDraftCommission / list / read")
    class PolicyAdministrationTests {

        @Test
        @DisplayName("updateDraftCommission on an unpublished draft with matching observedVersion changes the rate, saves, bumps revision")
        void updateDraftCommission_Draft_UpdatesAndSaves() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 3L, 0L);
            PolicyVersion afterSave = version(10L, 5L, new BigDecimal("0.0250"), false, DRAFT_CREATOR, null, 4L, 1L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft), Optional.of(afterSave));
            when(policyVersionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            PolicyVersion result = policyVersionService.updateDraftCommission(10L, new BigDecimal("0.0250"), 3L);

            assertThat(result.getCommissionRate()).isEqualByComparingTo("0.0250");
            ArgumentCaptor<PolicyVersion> captor = ArgumentCaptor.forClass(PolicyVersion.class);
            verify(policyVersionRepository).save(captor.capture());
            assertThat(captor.getValue().getCommissionRate()).isEqualByComparingTo("0.0250");
            verify(policyVersionRepository).incrementConfigurationRevision(10L);
        }

        @Test
        @DisplayName("updateDraftCommission with a stale observedVersion -> OptimisticVersionConflictException, no save")
        void updateDraftCommission_StaleVersion_RejectsNoSave() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 3L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> policyVersionService.updateDraftCommission(10L, new BigDecimal("0.03"), 1L))
                    .isInstanceOf(OptimisticVersionConflictException.class);

            verify(policyVersionRepository, never()).save(any());
            verify(policyVersionRepository, never()).incrementConfigurationRevision(any());
        }

        @Test
        @DisplayName("updateDraftCommission on a published version is rejected with IllegalStateException, no save")
        void updateDraftCommission_Published_RejectsNoSave() {
            PolicyVersion published = version(10L, 5L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(published));

            assertThatThrownBy(() -> policyVersionService.updateDraftCommission(10L, new BigDecimal("0.03"), 0L))
                    .isInstanceOf(IllegalStateException.class);

            verify(policyVersionRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateDraftCommission on an unknown id -> IllegalArgumentException with 'не найден' (404 semantics)")
        void updateDraftCommission_UnknownId_NotFound() {
            when(policyVersionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> policyVersionService.updateDraftCommission(999L, new BigDecimal("0.02"), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не найден");

            verify(policyVersionRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateDraftCommission with an out-of-range rate is rejected by the domain, no save")
        void updateDraftCommission_OutOfRange_RejectsNoSave() {
            PolicyVersion draft = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));

            assertThatThrownBy(() -> policyVersionService.updateDraftCommission(10L, new BigDecimal("1.5"), 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> policyVersionService.updateDraftCommission(10L, new BigDecimal("-0.01"), 0L))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(policyVersionRepository, never()).save(any());
        }

        @Test
        @DisplayName("listVersions(null) returns full history newest-first; listVersions(true/false) filters by published")
        void listVersions_DelegatesToRepositoryByFilter() {
            PolicyVersion a = version(2L, 2L, new BigDecimal("0.02"), true, DRAFT_CREATOR, null, 0L, 0L);
            PolicyVersion b = version(1L, 1L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findAllOrderByVersionNumberDesc()).thenReturn(List.of(a, b));
            when(policyVersionRepository.findByPublishedOrderByVersionNumberDesc(true)).thenReturn(List.of(a));
            when(policyVersionRepository.findByPublishedOrderByVersionNumberDesc(false)).thenReturn(List.of(b));

            assertThat(policyVersionService.listVersions(null)).containsExactly(a, b);
            assertThat(policyVersionService.listVersions(true)).containsExactly(a);
            assertThat(policyVersionService.listVersions(false)).containsExactly(b);
        }

        @Test
        @DisplayName("getViolations returns the configured rows; unknown policy id -> 404 semantics")
        void getViolations_KnownAndUnknown() {
            PolicyVersion v = version(10L, 5L, new BigDecimal("0.02"), false, DRAFT_CREATOR, null, 0L, 0L);
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(v));
            List<PolicyVersionViolation> rows = List.of(
                    new PolicyVersionViolation(1L, 10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now()));
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(rows);

            assertThat(policyVersionService.getViolations(10L)).isEqualTo(rows);

            when(policyVersionRepository.findById(999L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> policyVersionService.getViolations(999L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("не найден");
        }

        @Test
        @DisplayName("listViolationDefinitions returns the catalogue verbatim")
        void listViolationDefinitions_ReturnsCatalogue() {
            List<ViolationDefinition> catalogue = List.of(
                    new ViolationDefinition(1L, "SELLER_REFUSED_HANDOVER", "d", OffsetDateTime.now()));
            when(violationDefinitionRepository.findAll()).thenReturn(catalogue);

            assertThat(policyVersionService.listViolationDefinitions()).isEqualTo(catalogue);
        }
    }
}
