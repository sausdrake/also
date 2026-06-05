package com.example.autoauction.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataUserJpaRepository extends JpaRepository<JpaUserEntity, Long> {

    Optional<JpaUserEntity> findByUsername(String username);
    Optional<JpaUserEntity> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    List<JpaUserEntity> findByActive(boolean active);

    @Query("SELECT u FROM JpaUserEntity u JOIN u.roles r WHERE r.name = :roleName")
    List<JpaUserEntity> findByRoleName(@Param("roleName") String roleName);
}