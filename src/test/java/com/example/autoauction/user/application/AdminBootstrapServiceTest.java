package com.example.autoauction.user.application;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADM-01: first-administrator bootstrap rules. */
@DisplayName("ADM-01: AdminBootstrapService")
class AdminBootstrapServiceTest {

    private static final String GOOD_PASSWORD = "Adm01-strong-pass";
    private UserRepository users;
    private RoleRepository roles;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private AdminBootstrapService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        when(users.findByRoleName("ROLE_ADMIN")).thenReturn(List.of());
        when(roles.findByName("ROLE_ADMIN")).thenReturn(Optional.of(new Role(3L, "ROLE_ADMIN", "Администратор")));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AdminBootstrapService(users, roles, encoder);
    }

    @Test
    @DisplayName("creates one active ROLE_ADMIN user with a bcrypt hash of the password")
    void createsAdmin() {
        assertThat(service.bootstrap(" ops.admin ", " ops@also.example ", GOOD_PASSWORD)).isEqualTo(AdminBootstrapService.Outcome.CREATED);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getUsername()).isEqualTo("ops.admin");
        assertThat(admin.getEmail()).isEqualTo("ops@also.example");
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getRoles()).extracting(Role::getName).containsExactly("ROLE_ADMIN");
        assertThat(admin.getPasswordHash()).isNotEqualTo(GOOD_PASSWORD);
        assertThat(encoder.matches(GOOD_PASSWORD, admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("does nothing when an administrator already exists - even with otherwise invalid input")
    void skipsWhenAdminExists() {
        when(users.findByRoleName("ROLE_ADMIN")).thenReturn(List.of(mock(User.class)));

        assertThat(service.bootstrap("x", "not-an-email", "short")).isEqualTo(AdminBootstrapService.Outcome.SKIPPED_ADMIN_EXISTS);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("refuses short, username-equal and missing passwords")
    void refusesWeakPasswords() {
        assertThatThrownBy(() -> service.bootstrap("ops.admin", "ops@also.example", "elevenchars")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bootstrap("ops.admin.long", "ops@also.example", "OPS.ADMIN.LONG")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bootstrap("ops.admin", "ops@also.example", null)).isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("refuses every password of the dev-profile seed (they are public)")
    void refusesDevSeedPasswords() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/example/autoauction/config/DataInitializer.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("passwordEncoder\\.encode\\(\"([^\"]*)\"\\)").matcher(source);
        int seen = 0;
        while (m.find()) {
            String devPassword = m.group(1);
            seen++;
            assertThatThrownBy(() -> service.bootstrap("ops.admin", "ops@also.example", devPassword))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(seen).as("dev seed passwords found").isGreaterThan(0);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("refuses invalid usernames and emails")
    void refusesBadIdentity() {
        assertThatThrownBy(() -> service.bootstrap("ab", "ops@also.example", GOOD_PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bootstrap("has space", "ops@also.example", GOOD_PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bootstrap("ops.admin", "no-at-sign", GOOD_PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bootstrap(null, null, GOOD_PASSWORD)).isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("never promotes an existing user: a taken username or email is refused")
    void refusesTakenIdentity() {
        when(users.existsByUsername("taken")).thenReturn(true);
        when(users.existsByEmail("taken@also.example")).thenReturn(true);

        assertThatThrownBy(() -> service.bootstrap("taken", "free@also.example", GOOD_PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.bootstrap("free", "taken@also.example", GOOD_PASSWORD)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("fails clearly when ROLE_ADMIN is missing")
    void missingRole() {
        when(roles.findByName("ROLE_ADMIN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.bootstrap("ops.admin", "ops@also.example", GOOD_PASSWORD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROLE_ADMIN");
    }
}
