package com.example.autoauction.policy.application;

import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.port.PolicyVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
