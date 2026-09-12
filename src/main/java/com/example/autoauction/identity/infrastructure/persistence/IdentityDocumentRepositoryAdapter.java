package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class IdentityDocumentRepositoryAdapter implements IdentityDocumentRepository {

    private final SpringDataIdentityDocumentJpaRepository jpaRepository;
    private final IdentityDocumentMapper mapper;

    public IdentityDocumentRepositoryAdapter(SpringDataIdentityDocumentJpaRepository jpaRepository,
                                              IdentityDocumentMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public IdentityDocument save(IdentityDocument document) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(document)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentityDocument> findByStorageKey(String storageKey) {
        return jpaRepository.findByStorageKey(storageKey).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentityDocument> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }
}
