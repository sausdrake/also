package com.example.autoauction.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPasswordChangeRequirementJpaRepository
        extends JpaRepository<JpaPasswordChangeRequirementEntity, Long> {
}
