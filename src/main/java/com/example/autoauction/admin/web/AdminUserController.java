package com.example.autoauction.admin.web;

import com.example.autoauction.auth.application.PasswordService;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Управление пользователями", description = "Администрирование пользователей")
public class AdminUserController {

    private final UserService userService;
    private final DepositService depositService;
    private final PasswordService passwordService;

    @GetMapping
    @Operation(summary = "Список пользователей (фильтры: role, active, withBalance)")
    public List<UserDto> listUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "false") boolean withBalance
    ) {
        List<User> users;
        if (role != null && !role.isBlank()) {
            users = userService.listUsersByRole(role);
        } else if (active != null) {
            users = userService.listUsersByStatus(active);
        } else {
            users = userService.listUsers();
        }
        if (withBalance) {
            return users.stream().map(u -> {
                try {
                    DepositResponse dep = depositService.getDepositByUserId(u.getId());
                    return UserDto.fromWithBalance(u, dep);
                } catch (Exception e) {
                    return UserDto.fromWithBalance(u, null);
                }
            }).toList();
        }
        return users.stream().map(UserDto::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Детали пользователя")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        return userService.getUser(id)
                .map(UserDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/block")
    @Operation(summary = "Заблокировать пользователя")
    public ResponseEntity<UserDto> blockUser(@PathVariable Long id,
                                             @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return ResponseEntity.ok(UserDto.from(userService.blockUser(id, currentUser.getUserId())));
    }

    @PatchMapping("/{id}/unblock")
    @Operation(summary = "Разблокировать пользователя")
    public ResponseEntity<UserDto> unblockUser(@PathVariable Long id,
                                               @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return ResponseEntity.ok(UserDto.from(userService.unblockUser(id, currentUser.getUserId())));
    }

    /**
     * Slice 1A: records that the authenticated admin currently vouches this user is trusted to
     * bid/Buy Now in the controlled beta. This is an operational trust decision, never legal
     * identity verification. The acting admin is always taken from the authenticated principal -
     * never from the request body.
     */
    @PatchMapping("/{id}/trust")
    @Operation(summary = "Доверить пользователю участие в торгах (контролируемый бета-режим, не юридическая верификация)")
    public ResponseEntity<UserDto> trustUser(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        return ResponseEntity.ok(UserDto.from(userService.trustUser(id, currentUser.getUserId())));
    }

    @PatchMapping("/{id}/untrust")
    @Operation(summary = "Отозвать доверие пользователю для участия в торгах")
    public ResponseEntity<UserDto> untrustUser(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        return ResponseEntity.ok(UserDto.from(userService.untrustUser(id, currentUser.getUserId())));
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Сменить роль пользователя")
    public ResponseEntity<UserDto> setRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser
    ) {
        String roleName = body.get("role");
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("Поле role обязательно");
        }
        return ResponseEntity.ok(UserDto.from(userService.setUserRole(id, roleName, currentUser.getUserId())));
    }

    /** SEC-06: who blocked, unblocked, trusted or changed the roles of this user, and when (newest first). */
    /**
     * AUTH-01: resets a forgotten password to a one-time temporary password, returned here once.
     * Only after the caller's identity has been checked against the user's verified KYC document
     * (docs/ops/password-reset.md); the reset is journaled.
     */
    @PostMapping("/{id}/password-reset")
    @Operation(summary = "Сбросить пароль пользователя на временный (только после сверки личности с KYC)")
    public ResponseEntity<TemporaryPasswordDto> resetPassword(@PathVariable Long id,
                                                              @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        String temporaryPassword = passwordService.resetByAdmin(id, currentUser.getUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TemporaryPasswordDto(temporaryPassword));
    }

    public record TemporaryPasswordDto(String temporaryPassword) {}

    @GetMapping("/{id}/actions")
    @Operation(summary = "Журнал административных действий над пользователем")
    public List<AdminUserActionDto> adminActions(@PathVariable Long id) {
        return userService.listAdminActions(id).stream().map(AdminUserActionDto::from).toList();
    }

    public record AdminUserActionDto(Long id, Long targetUserId, Long adminId, String action, String details,
                                     java.time.OffsetDateTime createdAt) {
        static AdminUserActionDto from(com.example.autoauction.user.domain.AdminUserAction a) {
            return new AdminUserActionDto(a.id(), a.targetUserId(), a.adminId(), a.action().name(), a.details(), a.createdAt());
        }
    }

    // Slice 2 · Step 2A: the duplicate admin-adjust route previously hosted here has been removed.
    // The single canonical endpoint is POST /api/v1/admin/deposits/{userId}/adjust
    // (AdminDepositController.adjustDeposit). A fresh repository-wide search confirmed zero
    // remaining callers of this route (frontend and backend) before removal.

    @GetMapping("/{id}/deposit")
    @Operation(summary = "Баланс депозита пользователя")
    public ResponseEntity<DepositResponse> getUserDeposit(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(depositService.getDepositByUserId(id));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    public record UserDto(
            Long id,
            String username,
            String email,
            boolean active,
            boolean trusted,
            List<String> roles,
            java.math.BigDecimal balance,
            java.math.BigDecimal availableBalance,
            java.time.OffsetDateTime createdAt
    ) {
        static UserDto from(User u) {
            return new UserDto(
                    u.getId(), u.getUsername(), u.getEmail(), u.isActive(), u.isTrusted(),
                    u.getRoles().stream().map(r -> r.getName()).toList(),
                    null, null, u.getCreatedAt()
            );
        }

        static UserDto fromWithBalance(User u, DepositResponse dep) {
            return new UserDto(
                    u.getId(), u.getUsername(), u.getEmail(), u.isActive(), u.isTrusted(),
                    u.getRoles().stream().map(r -> r.getName()).toList(),
                    dep != null ? dep.balance() : null,
                    dep != null ? dep.availableBalance() : null,
                    u.getCreatedAt()
            );
        }
    }
}
