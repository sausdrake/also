package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEC-01: the UserDetails built for a user carries the user's own active flag. Every check that
 * relies on UserDetails.isEnabled() - the REST JWT filter, the STOMP CONNECT interceptor and the
 * login authentication provider - is only as good as this mapping.
 */
@DisplayName("SEC-01: UserDetailsServiceImpl maps User.active to UserDetails.isEnabled")
class UserDetailsServiceImplTest {

    private static User user(boolean active) {
        return new User(7L, "sec01", "sec01@test.com", "hash", active, Set.of(new Role(1L, "ROLE_USER", "u")));
    }

    @Test
    void blockedUser_isNotEnabled() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("sec01")).thenReturn(Optional.of(user(false)));

        var details = new UserDetailsServiceImpl(repo).loadUserByUsername("sec01");

        assertThat(details.isEnabled()).isFalse();
        assertThat(((UserPrincipal) details).getUserId()).isEqualTo(7L);
    }

    @Test
    void activeUser_isEnabled() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("sec01")).thenReturn(Optional.of(user(true)));

        var details = new UserDetailsServiceImpl(repo).loadUserByUsername("sec01");

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }
}
