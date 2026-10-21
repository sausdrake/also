package com.example.autoauction.user.infrastructure.persistence;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaUserRepositoryAdapter.class, JpaRoleRepositoryAdapter.class})
@DisplayName("Тесты сохранения пользователей с ролями")
class JpaUserRepositoryAdapterTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Role userRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {
        // Создаём тестовые роли
        userRole = roleRepository.save(new Role(null, "ROLE_USER", "Test user role"));
        adminRole = roleRepository.save(new Role(null, "ROLE_ADMIN", "Test admin role"));
    }

    @Test
    @DisplayName("Сохранение пользователя с ролями - роли должны сохраниться в user_roles")
    void saveUserWithRoles_ShouldSaveRoles() {
        // given
        User user = new User(
                null,
                "testuser",
                "test@example.com",
                "encoded_password",
                true,
                Set.of(userRole, adminRole)
        );

        // when
        User saved = userRepository.save(user);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRoles()).hasSize(2);
        assertThat(saved.getRoles()).extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        // Проверяем, что при повторной загрузке роли не теряются
        User loaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getRoles()).hasSize(2);
    }

    @Test
    @DisplayName("Поиск пользователя по username должен возвращать роли")
    void findByUsername_ShouldReturnUserWithRoles() {
        // given
        User user = new User(
                null,
                "johndoe",
                "john@example.com",
                "encoded_password",
                true,
                Set.of(userRole)
        );
        User saved = userRepository.save(user);

        // when
        User found = userRepository.findByUsername("johndoe").orElseThrow();

        // then
        assertThat(found.getRoles()).hasSize(1);
        assertThat(found.getRoles()).extracting(Role::getName).contains("ROLE_USER");
    }

    @Test
    @DisplayName("Пользователь без ролей - должен сохраниться с пустым набором")
    void saveUserWithoutRoles_ShouldSaveWithEmptyRoles() {
        // given
        User user = new User(
                null,
                "noroles",
                "noroles@example.com",
                "encoded_password",
                true,
                Set.of() // пустые роли
        );

        // when
        User saved = userRepository.save(user);

        // then
        assertThat(saved.getRoles()).isEmpty();
    }

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("BL-19/BL-23: createdAt is set on insert, read back, and never rewritten by an update")
    void createdAt_SetOnInsert_KeptOnUpdate() {
        User saved = userRepository.save(new User(null, "created", "created@example.com", "pw", true, Set.of(userRole)));
        assertThat(saved.getCreatedAt()).isNotNull();

        OffsetDateTime registered = OffsetDateTime.of(2025, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE users SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, registered).setParameter(2, saved.getId()).executeUpdate();
        entityManager.clear();

        User loaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isAtSameInstantAs(registered);

        // a full copy (the UserService pattern) keeps it
        User trusted = userRepository.save(new User(loaded.getId(), loaded.getUsername(), loaded.getEmail(),
                loaded.getPasswordHash(), loaded.isActive(), loaded.getRoles(), true, 7L, OffsetDateTime.now(),
                loaded.getCreatedAt()));
        assertThat(trusted.getCreatedAt()).isAtSameInstantAs(registered);

        // a copy that does not carry it (older constructor) still cannot rewrite the column
        userRepository.save(new User(loaded.getId(), loaded.getUsername(), loaded.getEmail(),
                loaded.getPasswordHash(), false, loaded.getRoles()));
        entityManager.flush();
        entityManager.clear();
        assertThat(userRepository.findById(saved.getId()).orElseThrow().getCreatedAt()).isAtSameInstantAs(registered);
    }
}