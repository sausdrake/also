package com.example.autoauction.policy.infrastructure.persistence;

import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PolicyVersionViolationRepositoryAdapter implements PolicyVersionViolationRepository {

    private final SpringDataPolicyVersionViolationJpaRepository jpaRepository;
    private final PolicyVersionViolationMapper mapper;

    public PolicyVersionViolationRepositoryAdapter(SpringDataPolicyVersionViolationJpaRepository jpaRepository,
                                                     PolicyVersionViolationMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    /**
     * Locked concurrency design, section C: always flushes explicitly, so that a first-time
     * insert's unique-constraint race ({@code uk_policy_version_violation}) surfaces
     * synchronously as {@link org.springframework.dao.DataIntegrityViolationException} to THIS
     * call, inside the caller's own transaction - never deferred to some later, unrelated flush
     * point where the caller could no longer attribute the failure to this specific write.
     */
    @Override
    public PolicyVersionViolation save(PolicyVersionViolation policyVersionViolation) {
        JpaPolicyVersionViolationEntity entity = mapper.toEntity(policyVersionViolation);
        JpaPolicyVersionViolationEntity saved = jpaRepository.saveAndFlush(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public List<PolicyVersionViolation> findByPolicyVersionId(Long policyVersionId) {
        return jpaRepository.findByPolicyVersionId(policyVersionId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<PolicyVersionViolation> findByPolicyVersionIdAndViolationDefinitionId(
            Long policyVersionId, Long violationDefinitionId) {
        return jpaRepository.findByPolicyVersionIdAndViolationDefinitionId(policyVersionId, violationDefinitionId)
                .map(mapper::toDomain);
    }
}
