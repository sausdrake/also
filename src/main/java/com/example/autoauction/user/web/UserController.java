package com.example.autoauction.user.web;

import com.example.autoauction.auth.infrastructure.security.SecurityUtils;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/users")
@Tag(name = "Пользователи", description = "API для управления пользователями")
public class UserController {

    private final UserService userService;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService,
                          RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        log.info("UserController initialized and ready.");
    }

    @GetMapping
    @Operation(summary = "Получить всех пользователей")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Успешно получен список пользователей"),
            @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера")
    })
    public List<UserResponse> getUsers() {
        // Только администраторы могут видеть всех пользователей
        if (!SecurityUtils.isAdmin()) {
            log.warn("Non-admin user tried to access all users list");
            throw new SecurityException("Только администраторы могут просматривать список всех пользователей");
        }

        return userService.listUsers().stream()
                .map(UserResponse::fromDomain)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить пользователя по ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Пользователь найден"),
            @ApiResponse(responseCode = "404", description = "Пользователь не найден"),
            @ApiResponse(responseCode = "400", description = "Некорректный ID")
    })
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "ID пользователя", example = "1", required = true)
            @PathVariable Long id
    ) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = SecurityUtils.isAdmin();

        // Пользователь может видеть только себя, если он не админ
        if (!isAdmin && !currentUserId.equals(id)) {
            log.warn("User {} tried to access user {} without permission", currentUserId, id);
            throw new SecurityException("Вы можете просматривать только свой профиль");
        }

        return userService.getUser(id)
                .map(UserResponse::fromDomain)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // SEC-02: creates accounts outside /api/auth/register (no rate limit, no registration rules),
    // so it is an administrative action only.
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создать нового пользователя (только администратор)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Пользователь успешно создан"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные"),
            @ApiResponse(responseCode = "409", description = "Пользователь с таким email или username уже существует")
    })
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        log.info("Received POST request for user creation with username: {}", request.username());

        // Проверяем существование
        if (userService.findByUsername(request.username()).isPresent()) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userService.findByEmail(request.email()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        // Получаем роль по умолчанию
        Role defaultRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("Default role ROLE_USER not found"));

        // Создаем пользователя с хешированным паролем
        User toCreate = new User(
                null,
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),  // ← Теперь хешируем!
                true,
                Set.of(defaultRole)  // ← Добавляем роль по умолчанию
        );

        User created = userService.createUser(toCreate);
        log.info("User created successfully with ID: {}", created.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.fromDomain(created));
    }

    @PostMapping("/{id}/roles")
    @Operation(summary = "Назначить роль пользователю (только для администраторов)")
    public ResponseEntity<UserResponse> assignRole(
            @PathVariable Long id,
            @Valid @RequestBody AssignRoleRequest request
    ) {
        // Проверяем права администратора
        if (!SecurityUtils.isAdmin()) {
            log.warn("Non-admin user tried to assign roles");
            throw new SecurityException("Только администраторы могут назначать роли");
        }

        User user = userService.getUser(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        Role role = roleRepository.findByName(request.roleName())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.roleName()));

        // Создаем нового пользователя с обновленными ролями
        Set<Role> newRoles = new java.util.HashSet<>(user.getRoles());
        newRoles.add(role);

        // Slice 1A: must preserve the existing trust decision - role assignment is an unrelated
        // action and must never silently reset trusted/verifiedByAdminId/verifiedAt.
        User updatedUser = new User(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isActive(),
                newRoles,
                user.isTrusted(),
                user.getVerifiedByAdminId(),
                user.getVerifiedAt()
        );

        User saved = userService.createUser(updatedUser);
        log.info("Role {} assigned to user {} by admin {}", role.getName(), id, SecurityUtils.getCurrentUsername());

        return ResponseEntity.ok(UserResponse.fromDomain(saved));
    }

    @GetMapping("/info")
    @Operation(summary = "Информация об API пользователей")
    public ResponseEntity<ApiInfo> getApiInfo() {
        return ResponseEntity.ok(new ApiInfo(
                "User API",
                "v1.0",
                List.of("GET /api/users", "GET /api/users/{id}", "POST /api/users", "POST /api/users/{id}/roles")
        ));
    }

    // DTO records с Swagger аннотациями
    public record CreateUserRequest(
            @Schema(description = "Имя пользователя", example = "john_doe", required = true, minLength = 3, maxLength = 50)
            @NotBlank @Size(min = 3, max = 50) String username,

            @Schema(description = "Email пользователя", example = "john@example.com", required = true)
            @NotBlank @Email String email,

            @Schema(description = "Пароль", example = "password123", required = true, minLength = 6, maxLength = 255)
            @NotBlank @Size(min = 6, max = 255) String password
    ) {}

    public record AssignRoleRequest(
            @Schema(description = "Название роли", example = "ROLE_DIAGNOSTIC", required = true)
            @NotBlank String roleName
    ) {}

    public record UserResponse(
            @Schema(description = "ID пользователя", example = "1")
            Long id,

            @Schema(description = "Имя пользователя", example = "john_doe")
            String username,

            @Schema(description = "Email пользователя", example = "john@example.com")
            String email,

            @Schema(description = "Активен ли пользователь", example = "true")
            boolean active,

            @Schema(description = "Роли пользователя")
            List<String> roles
    ) {
        public static UserResponse fromDomain(User user) {
            return new UserResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getEmail(),
                    user.isActive(),
                    user.getRoles().stream()
                            .map(Role::getName)
                            .toList()
            );
        }
    }

    public record ApiInfo(
            String name,
            String version,
            List<String> endpoints
    ) {}
}