package com.example.autoauction.auth.infrastructure.persistence;

import com.example.autoauction.auth.domain.port.PasswordChangeRequirementRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Component
public class PasswordChangeRequirementRepositoryAdapter implements PasswordChangeRequirementRepository {

    private final SpringDataPasswordChangeRequirementJpaRepository jpaRepository;

    public PasswordChangeRequirementRepositoryAdapter(SpringDataPasswordChangeRequirementJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void require(Long userId, Long resetByAdminId, OffsetDateTime at) {
        jpaRepository.save(new JpaPasswordChangeRequirementEntity(userId, resetByAdminId, at));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRequired(Long userId) {
        return userId != null && jpaRepository.existsById(userId);
    }

    @Override
    @Transactional
    public void clear(Long userId) {
        jpaRepository.deleteById(userId);
    }
}
