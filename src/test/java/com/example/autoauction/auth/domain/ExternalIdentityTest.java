package com.example.autoauction.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH-TELEGRAM-01: the identity is the subject; display data and an optional verified phone are metadata. */
class ExternalIdentityTest {

    private static final OffsetDateTime T = OffsetDateTime.of(2026, 10, 22, 10, 0, 0, 0, ZoneOffset.UTC);

    private static VerifiedExternalIdentity verified(String sub, String name, String username, String phone) {
        return new VerifiedExternalIdentity(ExternalIdentityProvider.TELEGRAM, sub, name, username, phone);
    }

    @Test
    @DisplayName("attach: new identity, bot access not requested, phone only when verified one came")
    void attach() {
        ExternalIdentity i = ExternalIdentity.attach(5L, verified("s1", "Name", "nick", null), T);
        assertThat(i.getUserId()).isEqualTo(5L);
        assertThat(i.getSubject()).isEqualTo("s1");
        assertThat(i.getBotAccess()).isEqualTo(BotAccess.NOT_REQUESTED);
        assertThat(i.hasVerifiedPhone()).isFalse();
        assertThat(i.getLastLoginAt()).isNull();
        ExternalIdentity withPhone = ExternalIdentity.attach(5L, verified("s2", null, null, "+996700000001"), T);
        assertThat(withPhone.hasVerifiedPhone()).isTrue();
        assertThat(withPhone.getPhoneVerifiedAt()).isEqualTo(T);
    }

    @Test
    @DisplayName("refresh: name / @username follow the provider; a token without a phone never erases a verified one")
    void refresh() {
        ExternalIdentity i = ExternalIdentity.attach(5L, verified("s1", "Old", "old", "+996700000001"), T);
        i.refreshProfile(verified("s1", "New", "new", null), T.plusDays(1));
        assertThat(i.getDisplayName()).isEqualTo("New");
        assertThat(i.getProviderUsername()).isEqualTo("new");
        assertThat(i.getPhoneNumber()).isEqualTo("+996700000001");
        assertThat(i.getPhoneVerifiedAt()).isEqualTo(T);
        assertThat(i.getUpdatedAt()).isEqualTo(T.plusDays(1));
        i.recordLogin(T.plusDays(2));
        assertThat(i.getLastLoginAt()).isEqualTo(T.plusDays(2));
    }

    @Test
    @DisplayName("refresh with another subject (or provider data of someone else) is refused")
    void otherSubjectRefused() {
        ExternalIdentity i = ExternalIdentity.attach(5L, verified("s1", "A", "a", null), T);
        assertThatThrownBy(() -> i.refreshProfile(verified("s2", "B", "b", null), T)).isInstanceOf(IllegalArgumentException.class);
        assertThat(i.getDisplayName()).isEqualTo("A");
    }

    @Test
    @DisplayName("a verified identity needs a subject and never prints it")
    void verifiedIdentity() {
        assertThatThrownBy(() -> verified(" ", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verified(null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(verified("secret-sub", "n", "u", "+996700000001").toString()).doesNotContain("secret-sub").doesNotContain("996");
    }

    @Test
    @DisplayName("an authorization request: link user only for LINK; expiry boundary")
    void authorizationRequest() {
        assertThatThrownBy(() -> new OidcAuthorizationRequest(null, "h", "b", "v", "n", OidcPurpose.LINK, null, "openid", T, T.plusMinutes(10), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OidcAuthorizationRequest(null, "h", "b", "v", "n", OidcPurpose.LOGIN, 3L, "openid", T, T.plusMinutes(10), null))
                .isInstanceOf(IllegalArgumentException.class);
        OidcAuthorizationRequest r = new OidcAuthorizationRequest(null, "h", "b", "v", "n", OidcPurpose.LOGIN, null, "openid", T, T.plusMinutes(10), null);
        assertThat(r.isExpiredAt(T.plusMinutes(10))).isFalse();
        assertThat(r.isExpiredAt(T.plusMinutes(10).plusNanos(1))).isTrue();
        assertThat(r.isConsumed()).isFalse();
    }
}
