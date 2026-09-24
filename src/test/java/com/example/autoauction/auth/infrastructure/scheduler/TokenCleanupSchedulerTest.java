package com.example.autoauction.auth.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.auth.domain.port.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Phase 79 (G-77-4): TokenCleanupScheduler is a Family-A thin orchestrator - one delegated
 * call, no per-candidate loop. Repository-level SQL correctness of
 * {@link RefreshTokenRepository#deleteExpiredTokens()} is out of scope here (Phase 78 residual
 * finding - it has no dedicated coverage anywhere in the repository).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenCleanupScheduler")
class TokenCleanupSchedulerTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private TokenCleanupScheduler scheduler;

    @Test
    @DisplayName("should delegate to RefreshTokenRepository.deleteExpiredTokens() and report success")
    void shouldDelegateToRepositoryAndReportSuccess() {
        scheduler.cleanupExpiredTokens();

        verify(refreshTokenRepository).deleteExpiredTokens();
        verify(schedulerHealthSignal).reportSuccess("TokenCleanupScheduler.cleanupExpiredTokens");
        verifyNoMoreInteractions(schedulerHealthSignal);
    }

    @Test
    @DisplayName("when repository throws, exception is swallowed and reportFailure is emitted instead of reportSuccess")
    void whenRepositoryThrows_ExceptionSwallowedAndFailureReported() {
        doThrow(new RuntimeException("simulated deletion failure"))
                .when(refreshTokenRepository).deleteExpiredTokens();

        scheduler.cleanupExpiredTokens(); // must not throw

        verify(schedulerHealthSignal).reportFailure("TokenCleanupScheduler.cleanupExpiredTokens");
        verify(schedulerHealthSignal, org.mockito.Mockito.never())
                .reportSuccess(org.mockito.ArgumentMatchers.anyString());
    }
}
