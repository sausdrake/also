package com.example.autoauction.user.application;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public UserService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        log.info("UserService initialized.");
    }

    public Optional<User> findByUsername(String username) { return userRepository.findByUsername(username); }
    public Optional<User> findByEmail(String email) { return userRepository.findByEmail(email); }
    public Optional<User> getUser(Long id) { return userRepository.findById(id); }

    public User createUser(User user) {
        log.info("Creating user: {}", user.getUsername());
        return userRepository.save(user);
    }

    public List<User> listUsers() { return userRepository.findAll(); }

    public List<User> listUsersByRole(String roleName) {
        return userRepository.findByRoleName(roleName);
    }

    public List<User> listUsersByStatus(boolean active) {
        return userRepository.findByActive(active);
    }

    @Transactional
    public User blockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        // Slice 1A: must preserve the existing trust decision - block/unblock is an unrelated
        // admin action and must never silently reset trusted/verifiedByAdminId/verifiedAt.
        User blocked = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), false, user.getRoles(),
                user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt());
        User saved = userRepository.save(blocked);
        log.info("User {} blocked", userId);
        return saved;
    }

    @Transactional
    public User unblockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        // Slice 1A: same preservation as blockUser above.
        User unblocked = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), true, user.getRoles(),
                user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt());
        User saved = userRepository.save(unblocked);
        log.info("User {} unblocked", userId);
        return saved;
    }

    /**
     * Slice 1A: records that {@code actingAdminId} currently vouches this user is trusted to
     * perform the protected controlled-beta participant actions (bidding, Buy Now). This is an
     * operational trust decision for the controlled beta - it is NOT legal identity verification
     * and must never be reported as KYC/passport verification.
     *
     * <p>verifiedByAdminId/verifiedAt always record who made the most recent trust-status
     * decision (grant or revoke) and when - never cleared on revoke, so an untrusted-after-review
     * user remains distinguishable from a never-reviewed user (both show trusted=false, but only
     * the former carries a non-null verifiedByAdminId/verifiedAt). There is no historical ledger
     * beyond this single current decision, by design - repeated TRUST calls (by the same or a
     * different admin) simply refresh this current-state metadata, safely and without creating
     * any contradictory state.
     */
    @Transactional
    public User trustUser(Long userId, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        User trusted = new User(user.getId(), user.getUsername(), user.getEmail(), user.getPasswordHash(),
                user.isActive(), user.getRoles(), true, actingAdminId, OffsetDateTime.now());
        User saved = userRepository.save(trusted);
        log.info("User {} trusted by admin {}", userId, actingAdminId);
        return saved;
    }

    @Transactional
    public User untrustUser(Long userId, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        User untrusted = new User(user.getId(), user.getUsername(), user.getEmail(), user.getPasswordHash(),
                user.isActive(), user.getRoles(), false, actingAdminId, OffsetDateTime.now());
        User saved = userRepository.save(untrusted);
        log.info("User {} untrusted by admin {}", userId, actingAdminId);
        return saved;
    }

    @Transactional
    public User setUserRole(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Роль не найдена: " + roleName));
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        // Slice 1A: same trust-preservation reasoning as blockUser/unblockUser - a role change
        // must never silently reset the existing trust decision.
        User updated = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), user.isActive(), roles,
                user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt());
        User saved = userRepository.save(updated);
        log.info("User {} role set to {}", userId, roleName);
        return saved;
    }

    public long countUsers() { return userRepository.findAll().size(); }

    public void deleteUser(Long id) { userRepository.deleteById(id); }
}

