package com.example.autoauction.user.application;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @InjectMocks
    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User(
                1L,
                "testuser",
                "test@example.com",
                "encodedPassword",
                true,
                Set.of()
        );
    }

    @Test
    void createUser_Success() {
        // given
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // when
        User created = userService.createUser(testUser);

        // then
        assertNotNull(created);
        assertEquals("testuser", created.getUsername());
        verify(userRepository, times(1)).save(testUser);
    }

    @Test
    void getUser_Exists() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        // when
        Optional<User> found = userService.getUser(1L);

        // then
        assertTrue(found.isPresent());
        assertEquals("testuser", found.get().getUsername());
    }

    @Test
    void getUser_NotFound() {
        // given
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // when
        Optional<User> found = userService.getUser(99L);

        // then
        assertFalse(found.isPresent());
    }

    @Test
    void findByUsername_Exists() {
        // given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));

        // when
        Optional<User> found = userService.findByUsername("testuser");

        // then
        assertTrue(found.isPresent());
        assertEquals("testuser", found.get().getUsername());
    }

    @Test
    void findByEmail_Exists() {
        // given
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // when
        Optional<User> found = userService.findByEmail("test@example.com");

        // then
        assertTrue(found.isPresent());
        assertEquals("test@example.com", found.get().getEmail());
    }

    @Test
    void listUsers() {
        // given
        List<User> users = Arrays.asList(
                testUser,
                new User(2L, "user2", "user2@example.com", "pass", true, Set.of())
        );
        when(userRepository.findAll()).thenReturn(users);

        // when
        List<User> found = userService.listUsers();

        // then
        assertEquals(2, found.size());
    }

    @Test
    void deleteUser() {
        // given
        doNothing().when(userRepository).deleteById(1L);

        // when
        userService.deleteUser(1L);

        // then
        verify(userRepository, times(1)).deleteById(1L);
    }

    // ==================== Slice 1A: trust gate ====================

    @Test
    void trustUser_Success_SetsTrustedAndAdminMetadata() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User trusted = userService.trustUser(1L, 42L);

        assertTrue(trusted.isTrusted());
        assertEquals(42L, trusted.getVerifiedByAdminId());
        assertNotNull(trusted.getVerifiedAt());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void trustUser_NonExistentUser_Throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> userService.trustUser(99L, 42L));
        verify(userRepository, never()).save(any());
    }

    @Test
    void untrustUser_Success_ClearsTrustedButRecordsCurrentDecisionMetadata() {
        User alreadyTrusted = new User(1L, "testuser", "test@example.com", "encodedPassword",
                true, Set.of(), true, 7L, java.time.OffsetDateTime.now().minusDays(1));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alreadyTrusted));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User untrusted = userService.untrustUser(1L, 42L);

        assertFalse(untrusted.isTrusted());
        // verifiedByAdminId/verifiedAt record the most recent trust-status DECISION (grant or
        // revoke) - never cleared, so a revoked-after-review user stays distinguishable from a
        // never-reviewed one.
        assertEquals(42L, untrusted.getVerifiedByAdminId());
        assertNotNull(untrusted.getVerifiedAt());
    }

    @Test
    void untrustUser_NonExistentUser_Throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> userService.untrustUser(99L, 42L));
        verify(userRepository, never()).save(any());
    }

    @Test
    void trustUser_RepeatedCall_SafeAndRefreshesMetadata() {
        User alreadyTrusted = new User(1L, "testuser", "test@example.com", "encodedPassword",
                true, Set.of(), true, 7L, java.time.OffsetDateTime.now().minusDays(1));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alreadyTrusted));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.trustUser(1L, 99L);

        assertTrue(result.isTrusted());
        assertEquals(99L, result.getVerifiedByAdminId());
    }

    @Test
    void blockUser_PreservesExistingTrustState() {
        User trustedActive = new User(1L, "testuser", "test@example.com", "encodedPassword",
                true, Set.of(), true, 7L, java.time.OffsetDateTime.now());
        when(userRepository.findById(1L)).thenReturn(Optional.of(trustedActive));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User blocked = userService.blockUser(1L);

        assertFalse(blocked.isActive());
        assertTrue(blocked.isTrusted());
        assertEquals(7L, blocked.getVerifiedByAdminId());
    }

    @Test
    void setUserRole_PreservesExistingTrustState() {
        User trustedActive = new User(1L, "testuser", "test@example.com", "encodedPassword",
                true, Set.of(), true, 7L, java.time.OffsetDateTime.now());
        Role newRole = new Role(2L, "ROLE_DIAGNOSTIC", "Diagnostic");
        when(userRepository.findById(1L)).thenReturn(Optional.of(trustedActive));
        when(roleRepository.findByName("ROLE_DIAGNOSTIC")).thenReturn(Optional.of(newRole));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = userService.setUserRole(1L, "ROLE_DIAGNOSTIC");

        assertTrue(updated.isTrusted());
        assertEquals(7L, updated.getVerifiedByAdminId());
    }
}