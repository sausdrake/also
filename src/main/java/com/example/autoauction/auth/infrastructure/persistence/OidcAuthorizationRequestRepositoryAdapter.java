package com.example.autoauction.auth.infrastructure.persistence;

import com.example.autoauction.auth.domain.OidcAuthorizationRequest;
import com.example.autoauction.auth.domain.port.OidcAuthorizationRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Component
public class OidcAuthorizationRequestRepositoryAdapter implements OidcAuthorizationRequestRepository {

    private final SpringDataOidcAuthorizationRequestJpaRepository jpaRepository;

    public OidcAuthorizationRequestRepositoryAdapter(SpringDataOidcAuthorizationRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public OidcAuthorizationRequest save(OidcAuthorizationRequest r) {
        return toDomain(jpaRepository.save(new JpaOidcAuthorizationRequestEntity(r.getId(), r.getStateHash(), r.getBindingHash(),
                r.getCodeVerifier(), r.getNonce(), r.getPurpose(), r.getLinkUserId(), r.getScope(), r.getCreatedAt(),
                r.getExpiresAt(), r.getConsumedAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OidcAuthorizationRequest> findByStateHash(String stateHash) {
        return jpaRepository.findByStateHash(stateHash).map(OidcAuthorizationRequestRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public boolean markConsumed(Long id, OffsetDateTime at) {
        return jpaRepository.markConsumed(id, at) == 1;
    }

    @Override
    @Transactional
    public int deleteExpiredBefore(OffsetDateTime before) {
        return jpaRepository.deleteExpiredBefore(before);
    }

    private static OidcAuthorizationRequest toDomain(JpaOidcAuthorizationRequestEntity e) {
        return new OidcAuthorizationRequest(e.getId(), e.getStateHash(), e.getBindingHash(), e.getCodeVerifier(), e.getNonce(),
                e.getPurpose(), e.getLinkUserId(), e.getScope(), e.getCreatedAt(), e.getExpiresAt(), e.getConsumedAt());
    }
}
