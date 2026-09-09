package com.example.autoauction.policy.infrastructure.persistence;

import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ViolationDefinitionRepositoryAdapter implements ViolationDefinitionRepository {

    private final SpringDataViolationDefinitionJpaRepository jpaRepository;
    private final ViolationDefinitionMapper mapper;

    public ViolationDefinitionRepositoryAdapter(SpringDataViolationDefinitionJpaRepository jpaRepository,
                                                 ViolationDefinitionMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public List<ViolationDefinition> findAll() {
        return jpaRepository.findAll().stream().map(mapper::toDomain).toList();
    }

    @Override
    public Optional<ViolationDefinition> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }
}
