package com.example.autoauction.admin.web;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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
    public ResponseEntity<UserDto> blockUser(@PathVariable Long id) {
        return ResponseEntity.ok(UserDto.from(userService.blockUser(id)));
    }

    @PatchMapping("/{id}/unblock")
    @Operation(summary = "Разблокировать пользователя")
    public ResponseEntity<UserDto> unblockUser(@PathVariable Long id) {
        return ResponseEntity.ok(UserDto.from(userService.unblockUser(id)));
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Сменить роль пользователя")
    public ResponseEntity<UserDto> setRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        String roleName = body.get("role");
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("Поле role обязательно");
        }
        return ResponseEntity.ok(UserDto.from(userService.setUserRole(id, roleName)));
    }

    @PostMapping("/{id}/deposit/adjust")
    @Operation(summary = "Ручная корректировка депозита пользователя (положительная — пополнение, отрицательная — списание)")
    public ResponseEntity<DepositResponse> adjustDeposit(
            @PathVariable Long id,
            @Valid @RequestBody AdjustDepositRequest body
    ) {
        return ResponseEntity.ok(depositService.adminAdjust(id, body.amount(), body.comment()));
    }

    record AdjustDepositRequest(
            @NotNull(message = "Сумма обязательна") BigDecimal amount,
            @NotBlank(message = "Комментарий обязателен") String comment
    ) {}

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
            List<String> roles,
            java.math.BigDecimal balance,
            java.math.BigDecimal availableBalance
    ) {
        static UserDto from(User u) {
            return new UserDto(
                    u.getId(), u.getUsername(), u.getEmail(), u.isActive(),
                    u.getRoles().stream().map(r -> r.getName()).toList(),
                    null, null
            );
        }

        static UserDto fromWithBalance(User u, DepositResponse dep) {
            return new UserDto(
                    u.getId(), u.getUsername(), u.getEmail(), u.isActive(),
                    u.getRoles().stream().map(r -> r.getName()).toList(),
                    dep != null ? dep.balance() : null,
                    dep != null ? dep.availableBalance() : null
            );
        }
    }
}
