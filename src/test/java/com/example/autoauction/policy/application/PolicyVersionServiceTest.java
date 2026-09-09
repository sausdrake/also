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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slice 1b-2: deterministic, mocked-repository unit tests for
 * {@link PolicyVersionService#getCurrentPublished()} - specifically the fail-closed guarantee,
 * which cannot be reliably exercised in {@link PolicyVersionServiceIntegrationTest} because that
 * suite shares one non-transactional database across all its test methods (some of which publish
 * policies), so "no PolicyVersion is currently published" is not a state that test class can
 * deterministically produce.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PolicyVersionService.getCurrentPublished() — Slice 1b-2 unit tests")
class PolicyVersionServiceTest {

    @Mock
    private PolicyVersionRepository policyVersionRepository;

    @Mock
    private ViolationDefinitionRepository violationDefinitionRepository;

    @Mock
    private PolicyVersionViolationRepository policyVersionViolationRepository;

    @InjectMocks
    private PolicyVersionService policyVersionService;

    @Test
    @DisplayName("getCurrentPublished() throws IllegalStateException when no PolicyVersion is published (fail closed)")
    void getCurrentPublished_WhenNonePublished_ShouldThrowIllegalStateException() {
        when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> policyVersionService.getCurrentPublished());
    }

    @Test
    @DisplayName("getCurrentPublished() returns exactly what the repository resolves as current")
    void getCurrentPublished_WhenOnePublished_ShouldReturnIt() {
        PolicyVersion published = new PolicyVersion(5L, 3L, new BigDecimal("0.02"), true,
                OffsetDateTime.now(), 1L, OffsetDateTime.now());
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
            PolicyVersion draft = new PolicyVersion(10L, 5L, new BigDecimal("0.02"), false,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.save(any())).thenReturn(draft);
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.empty());

            PolicyVersion result = policyVersionService.create(new BigDecimal("0.02"), 1L);

            assertEquals(10L, result.getId());
            verify(policyVersionViolationRepository, never()).findByPolicyVersionId(any());
            verify(policyVersionViolationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Previously published PolicyVersion exists -> every one of its violation rows is copied to the new draft")
        void create_PreviousPublishedExists_CopiesForwardEveryViolationRow() {
            PolicyVersion previous = new PolicyVersion(9L, 4L, new BigDecimal("0.02"), true,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            PolicyVersion draft = new PolicyVersion(10L, 5L, new BigDecimal("0.03"), false,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.save(any())).thenReturn(draft);
            when(policyVersionRepository.findCurrentPublished()).thenReturn(Optional.of(previous));
            List<PolicyVersionViolation> previousViolations = List.of(
                    new PolicyVersionViolation(100L, 9L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now()),
                    new PolicyVersionViolation(101L, 9L, 2L, PenaltyType.PERCENTAGE, new BigDecimal("50"), OffsetDateTime.now()));
            when(policyVersionViolationRepository.findByPolicyVersionId(9L)).thenReturn(previousViolations);
            when(policyVersionViolationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            policyVersionService.create(new BigDecimal("0.03"), 1L);

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
    @DisplayName("X3-E: publish() completeness check")
    class PublishCompletenessTests {

        @Test
        @DisplayName("Every catalogue violation configured -> publish succeeds")
        void publish_CompleteConfiguration_Succeeds() {
            PolicyVersion published = new PolicyVersion(10L, 5L, new BigDecimal("0.02"), true,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(published));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of(
                    new ViolationDefinition(1L, "A", "d", OffsetDateTime.now()),
                    new ViolationDefinition(2L, "B", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of(
                    new PolicyVersionViolation(100L, 10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now()),
                    new PolicyVersionViolation(101L, 10L, 2L, PenaltyType.PERCENTAGE, new BigDecimal("50"), OffsetDateTime.now())));
            when(policyVersionRepository.claimPublish(10L)).thenReturn(true);

            PolicyVersion result = policyVersionService.publish(10L);

            assertThat(result.isPublished()).isTrue();
        }

        @Test
        @DisplayName("Missing violation configuration -> fails closed before claiming publish, no mutation")
        void publish_IncompleteConfiguration_FailsClosedBeforeClaim() {
            when(violationDefinitionRepository.findAll()).thenReturn(List.of(
                    new ViolationDefinition(1L, "SELLER_REFUSED_HANDOVER", "d", OffsetDateTime.now()),
                    new ViolationDefinition(2L, "SELLER_UNREACHABLE", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of(
                    new PolicyVersionViolation(100L, 10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), OffsetDateTime.now())));
            // violation id 2 (SELLER_UNREACHABLE) has no configured row.

            assertThatThrownBy(() -> policyVersionService.publish(10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SELLER_UNREACHABLE");

            verify(policyVersionRepository, never()).claimPublish(any());
        }

        @Test
        @DisplayName("Empty catalogue (no ViolationDefinition rows at all) -> publish trivially succeeds")
        void publish_EmptyCatalogue_TriviallySucceeds() {
            PolicyVersion published = new PolicyVersion(10L, 5L, new BigDecimal("0.02"), true,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(published));
            when(violationDefinitionRepository.findAll()).thenReturn(List.of());
            when(policyVersionViolationRepository.findByPolicyVersionId(10L)).thenReturn(List.of());
            when(policyVersionRepository.claimPublish(10L)).thenReturn(true);

            PolicyVersion result = policyVersionService.publish(10L);

            assertThat(result.isPublished()).isTrue();
        }
    }

    @Nested
    @DisplayName("X3-E: updateDraftViolation()")
    class UpdateDraftViolationTests {

        @Test
        @DisplayName("Target PolicyVersion already published -> IllegalStateException, no mutation")
        void updateDraftViolation_AlreadyPublished_Rejects() {
            PolicyVersion published = new PolicyVersion(10L, 5L, new BigDecimal("0.02"), true,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(published));

            assertThatThrownBy(() -> policyVersionService.updateDraftViolation(10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50")))
                    .isInstanceOf(IllegalStateException.class);

            verify(policyVersionViolationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Unpublished draft, no existing row for this violation -> inserts a new one")
        void updateDraftViolation_DraftNoExistingRow_Inserts() {
            PolicyVersion draft = new PolicyVersion(10L, 5L, new BigDecimal("0.02"), false,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findById(1L))
                    .thenReturn(Optional.of(new ViolationDefinition(1L, "A", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(10L, 1L))
                    .thenReturn(Optional.empty());
            when(policyVersionViolationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            policyVersionService.updateDraftViolation(10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50"));

            ArgumentCaptor<PolicyVersionViolation> captor = ArgumentCaptor.forClass(PolicyVersionViolation.class);
            verify(policyVersionViolationRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isNull();
        }

        @Test
        @DisplayName("Unpublished draft, existing row for this violation -> replaces it (same id)")
        void updateDraftViolation_DraftExistingRow_ReplacesWithSameId() {
            PolicyVersion draft = new PolicyVersion(10L, 5L, new BigDecimal("0.02"), false,
                    OffsetDateTime.now(), 1L, OffsetDateTime.now());
            when(policyVersionRepository.findById(10L)).thenReturn(Optional.of(draft));
            when(violationDefinitionRepository.findById(1L))
                    .thenReturn(Optional.of(new ViolationDefinition(1L, "A", "d", OffsetDateTime.now())));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(10L, 1L))
                    .thenReturn(Optional.of(new PolicyVersionViolation(200L, 10L, 1L, PenaltyType.PERCENTAGE,
                            new BigDecimal("100"), OffsetDateTime.now())));
            when(policyVersionViolationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            policyVersionService.updateDraftViolation(10L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("50"));

            ArgumentCaptor<PolicyVersionViolation> captor = ArgumentCaptor.forClass(PolicyVersionViolation.class);
            verify(policyVersionViolationRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(200L);
            assertThat(captor.getValue().getPenaltyValue()).isEqualByComparingTo("50");
        }
    }
}
