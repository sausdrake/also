package com.example.autoauction.user.infrastructure.persistence;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public final class UserMapper {

    private UserMapper() {}

    public static User toDomain(JpaUserEntity entity) {
        if (entity == null) return null;

        Set<Role> roles = entity.getRoles().stream()
                .map(UserMapper::toDomain)
                .collect(Collectors.toSet());

        return new User(
                entity.getId(),
                entity.getUsername(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.isActive(),
                roles
        );
    }

    public static Role toDomain(JpaRoleEntity entity) {
        if (entity == null) return null;

        return new Role(
                entity.getId(),
                entity.getName(),
                entity.getDescription()
        );
    }

    public static JpaUserEntity toEntity(User domain, SpringDataRoleJpaRepository roleRepository) {
        if (domain == null) return null;

        JpaUserEntity entity = new JpaUserEntity(
                domain.getUsername(),
                domain.getEmail(),
                domain.getPasswordHash(),
                domain.isActive()
        );
        entity.setId(domain.getId());

        // Загружаем роли из БД, а не создаём новые transient объекты
        Set<JpaRoleEntity> roleEntities = domain.getRoles().stream()
                .map(role -> roleRepository.findByName(role.getName())
                        .orElseThrow(() -> new IllegalStateException("Role not found: " + role.getName())))
                .collect(Collectors.toSet());
        entity.setRoles(roleEntities);

        return entity;
    }

    // Этот метод больше не нужен, но оставим для других мест, где может использоваться
    public static JpaRoleEntity toEntity(Role domain) {
        if (domain == null) return null;

        // Создаём новую роль без ID (ID сгенерируется при сохранении)
        return new JpaRoleEntity(
                domain.getName(),
                domain.getDescription()
        );
    }
}