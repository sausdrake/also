package com.example.autoauction.identity.application;

import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MVP-004: VerifiedIdentityGate - only a VERIFIED identity passes")
class VerifiedIdentityGateTest {

    private static final Long USER_ID = 42L;

    private final IdentityVerificationRepository repository = mock(IdentityVerificationRepository.class);
    private final VerifiedIdentityGate gate = new VerifiedIdentityGate(repository);

    private static IdentityVerification verificationWithStatus(IdentityVerificationStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        IdentityVerification iv = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "AN1234567", 7L, 7L, now);
        switch (status) {
            case PENDING_REVIEW -> { }
            case VERIFIED -> iv.approve(now);
            case REJECTED -> iv.reject(now);
            case REVOKED -> { iv.approve(now); iv.revoke(now); }
        }
        assertThat(iv.getStatus()).isEqualTo(status);
        return iv;
    }

    @Test
    @DisplayName("VERIFIED passes")
    void verified_passes() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(verificationWithStatus(IdentityVerificationStatus.VERIFIED)));

        assertThat(gate.isVerified(USER_ID)).isTrue();
        assertThatCode(() -> gate.requireVerified(USER_ID)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} is refused")
    @EnumSource(value = IdentityVerificationStatus.class, names = "VERIFIED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStatus_isRefused(IdentityVerificationStatus status) {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(verificationWithStatus(status)));

        assertThat(gate.isVerified(USER_ID)).isFalse();
        assertThatThrownBy(() -> gate.requireVerified(USER_ID))
                .isInstanceOf(IdentityVerificationRequiredException.class)
                .extracting(e -> ((IdentityVerificationRequiredException) e).getCode())
                .isEqualTo("IDENTITY_VERIFICATION_REQUIRED");
    }

    @Test
    @DisplayName("never submitted (no row) is refused")
    void noVerification_isRefused() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(gate.isVerified(USER_ID)).isFalse();
        assertThatThrownBy(() -> gate.requireVerified(USER_ID)).isInstanceOf(IdentityVerificationRequiredException.class);
    }

    @Test
    @DisplayName("null user id is refused without touching the repository")
    void nullUserId_isRefused() {
        assertThatThrownBy(() -> gate.requireVerified(null)).isInstanceOf(IdentityVerificationRequiredException.class);
        verify(repository, never()).findByUserId(any());
    }
}
