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

    @Override
    public PolicyVersionViolation save(PolicyVersionViolation policyVersionViolation) {
        JpaPolicyVersionViolationEntity entity = mapper.toEntity(policyVersionViolation);
        JpaPolicyVersionViolationEntity saved = jpaRepository.save(entity);
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
