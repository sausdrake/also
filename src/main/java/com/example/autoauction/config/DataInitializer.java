package com.example.autoauction.config;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
@Profile("dev")
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // 1. Сначала создаём ВСЕ роли
        Role userRole = createRoleIfNotExists("ROLE_USER", "Обычный пользователь");
        Role diagnosticRole = createRoleIfNotExists("ROLE_DIAGNOSTIC", "Диагностик");
        Role adminRole = createRoleIfNotExists("ROLE_ADMIN", "Администратор");

        // 2. Создаём администратора (только если нет)
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User(
                    null,
                    "admin",
                    "admin@example.com",
                    passwordEncoder.encode("admin123"),
                    true,
                    Set.of(adminRole)  // ← adminRole уже сохранён в БД
            );
            userRepository.save(admin);
            System.out.println("Admin user created with ADMIN role!");
        }

        // 3. Создаём диагностика
        if (userRepository.findByUsername("diagnostic").isEmpty()) {
            User diagnostic = new User(
                    null,
                    "diagnostic",
                    "diagnostic@example.com",
                    passwordEncoder.encode("diagnostic123"),
                    true,
                    Set.of(diagnosticRole)
            );
            userRepository.save(diagnostic);
            System.out.println("Diagnostic user created!");
        }

        // 4. Создаём обычного пользователя
        if (userRepository.findByUsername("user").isEmpty()) {
            User regularUser = new User(
                    null,
                    "user",
                    "user@example.com",
                    passwordEncoder.encode("user123"),
                    true,
                    Set.of(userRole)
            );
            userRepository.save(regularUser);
            System.out.println("Regular user created!");
        }
    }

    private Role createRoleIfNotExists(String roleName, String description) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(new Role(null, roleName, description)));
    }
}