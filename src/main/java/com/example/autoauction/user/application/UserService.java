package com.example.autoauction.user.application;

import com.example.autoauction.user.domain.AdminUserAction;
import com.example.autoauction.user.domain.AdminUserActionType;
import com.example.autoauction.user.domain.port.AdminUserActionRepository;
import java.util.stream.Collectors;
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
    private final AdminUserActionRepository adminUserActionRepository;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       AdminUserActionRepository adminUserActionRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.adminUserActionRepository = adminUserActionRepository;
        log.info("UserService initialized.");
    }

    /**
     * SEC-06: journals an administrative action in the caller's transaction - if the action rolls
     * back, so does its journal row, and an action is never stored without its actor.
     */
    private void journal(Long targetUserId, Long actingAdminId, AdminUserActionType action, String details) {
        adminUserActionRepository.append(AdminUserAction.of(targetUserId, actingAdminId, action, details, OffsetDateTime.now()));
    }

    private static String roleNames(Set<Role> roles) {
        return roles.stream().map(Role::getName).sorted().collect(Collectors.joining(",", "[", "]"));
    }

    /** SEC-06: the journal of administrative actions on one user, newest first. */
    public List<AdminUserAction> listAdminActions(Long userId) {
        return adminUserActionRepository.findByTargetUserIdNewestFirst(userId);
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
    public User blockUser(Long userId, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        // Slice 1A: must preserve the existing trust decision - block/unblock is an unrelated
        // admin action and must never silently reset trusted/verifiedByAdminId/verifiedAt.
        User blocked = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), false, user.getRoles(),
                user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt());
        User saved = userRepository.save(blocked);
        journal(userId, actingAdminId, AdminUserActionType.BLOCK, null);
        log.info("User {} blocked by admin {}", userId, actingAdminId);
        return saved;
    }

    @Transactional
    public User unblockUser(Long userId, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        // Slice 1A: same preservation as blockUser above.
        User unblocked = new User(user.getId(), user.getUsername(), user.getEmail(),
                user.getPasswordHash(), true, user.getRoles(),
                user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt());
        User saved = userRepository.save(unblocked);
        journal(userId, actingAdminId, AdminUserActionType.UNBLOCK, null);
        log.info("User {} unblocked by admin {}", userId, actingAdminId);
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
     * the former carries a non-null verifiedByAdminId/verifiedAt). The user row keeps only this
     * single current decision, by design - repeated TRUST calls (by the same or a different admin)
     * simply refresh this current-state metadata. SEC-06: every grant and revoke is additionally
     * journaled in admin_user_actions, which is history, not state.
     */
    @Transactional
    public User trustUser(Long userId, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        User trusted = new User(user.getId(), user.getUsername(), user.getEmail(), user.getPasswordHash(),
                user.isActive(), user.getRoles(), true, actingAdminId, OffsetDateTime.now());
        User saved = userRepository.save(trusted);
        journal(userId, actingAdminId, AdminUserActionType.TRUST, null);
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
        journal(userId, actingAdminId, AdminUserActionType.UNTRUST, null);
        log.info("User {} untrusted by admin {}", userId, actingAdminId);
        return saved;
    }

    @Transactional
    public User setUserRole(Long userId, String roleName, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
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
        journal(userId, actingAdminId, AdminUserActionType.ROLE_SET, roleNames(user.getRoles()) + " -> " + roleNames(roles));
        log.info("User {} role set to {} by admin {}", userId, roleName, actingAdminId);
        return saved;
    }

    /**
     * SEC-06: adds a role to the user's existing roles (formerly done inline in
     * UserController.assignRole without any journal). Preserves the trust decision.
     */
    @Transactional
    public User addUserRole(Long userId, String roleName, Long actingAdminId) {
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
        Set<Role> roles = new HashSet<>(user.getRoles());
        roles.add(role);
        User updated = new User(user.getId(), user.getUsername(), user.getEmail(), user.getPasswordHash(),
                user.isActive(), roles, user.isTrusted(), user.getVerifiedByAdminId(), user.getVerifiedAt());
        User saved = userRepository.save(updated);
        journal(userId, actingAdminId, AdminUserActionType.ROLE_ADD, roleNames(user.getRoles()) + " -> " + roleNames(roles));
        log.info("Role {} added to user {} by admin {}", roleName, userId, actingAdminId);
        return saved;
    }

    public long countUsers() { return userRepository.findAll().size(); }

    public void deleteUser(Long id) { userRepository.deleteById(id); }
}

