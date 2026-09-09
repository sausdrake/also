package com.example.autoauction.policy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataViolationDefinitionJpaRepository extends JpaRepository<JpaViolationDefinitionEntity, Long> {
}
