package com.example.autoauction.user.infrastructure.persistence;

import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Repository
@Transactional
public class JpaUserRepositoryAdapter implements UserRepository {

    private final SpringDataUserJpaRepository jpaRepository;
    private final SpringDataRoleJpaRepository roleJpaRepository;

    public JpaUserRepositoryAdapter(SpringDataUserJpaRepository jpaRepository,
                                    SpringDataRoleJpaRepository roleJpaRepository) {
        this.jpaRepository = jpaRepository;
        this.roleJpaRepository = roleJpaRepository;
    }

    @Override
    public User save(User user) {
        // Конвертируем User в JpaUserEntity
        JpaUserEntity entity = new JpaUserEntity(
                user.getUsername(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isActive()
        );
        entity.setId(user.getId());

        // Конвертируем роли (загружаем существующие из БД)
        Set<JpaRoleEntity> roleEntities = user.getRoles().stream()
                .map(role -> roleJpaRepository.findByName(role.getName())
                        .orElseGet(() -> {
                            // Если роли нет в БД, создаём новую
                            JpaRoleEntity newRole = new JpaRoleEntity(role.getName(), role.getDescription());
                            return roleJpaRepository.save(newRole);
                        }))
                .collect(Collectors.toSet());
        entity.setRoles(roleEntities);

        JpaUserEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    private User toDomain(JpaUserEntity entity) {
        Set<com.example.autoauction.user.domain.Role> roles = entity.getRoles().stream()
                .map(roleEntity -> new com.example.autoauction.user.domain.Role(
                        roleEntity.getId(),
                        roleEntity.getName(),
                        roleEntity.getDescription()
                ))
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

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findAll() {
        return jpaRepository.findAll().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findByActive(boolean active) {
        return jpaRepository.findByActive(active).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findByRoleName(String roleName) {
        return jpaRepository.findByRoleName(roleName).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }
}