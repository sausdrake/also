package com.example.autoauction.policy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataPolicyVersionViolationJpaRepository extends JpaRepository<JpaPolicyVersionViolationEntity, Long> {

    List<JpaPolicyVersionViolationEntity> findByPolicyVersionId(Long policyVersionId);

    Optional<JpaPolicyVersionViolationEntity> findByPolicyVersionIdAndViolationDefinitionId(
            Long policyVersionId, Long violationDefinitionId);
}
