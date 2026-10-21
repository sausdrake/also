package com.example.autoauction.auth.infrastructure.persistence;

import com.example.autoauction.auth.domain.ExternalIdentity;
import com.example.autoauction.auth.domain.ExternalIdentityProvider;
import com.example.autoauction.auth.domain.port.ExternalIdentityRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class ExternalIdentityRepositoryAdapter implements ExternalIdentityRepository {

    private final SpringDataExternalIdentityJpaRepository jpaRepository;

    public ExternalIdentityRepositoryAdapter(SpringDataExternalIdentityJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExternalIdentity> findByProviderAndSubject(ExternalIdentityProvider provider, String subject) {
        return jpaRepository.findByProviderAndSubject(provider, subject).map(ExternalIdentityRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExternalIdentity> findByUserIdAndProvider(Long userId, ExternalIdentityProvider provider) {
        return jpaRepository.findByUserIdAndProvider(userId, provider).map(ExternalIdentityRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public ExternalIdentity save(ExternalIdentity d) {
        // saveAndFlush: a UNIQUE violation must surface here, inside the caller's transaction, not at commit
        return toDomain(jpaRepository.saveAndFlush(new JpaExternalIdentityEntity(d.getId(), d.getUserId(), d.getProvider(),
                d.getSubject(), d.getDisplayName(), d.getProviderUsername(), d.getPhoneNumber(), d.getPhoneVerifiedAt(),
                d.getBotAccess(), d.getCreatedAt(), d.getUpdatedAt(), d.getLastLoginAt())));
    }

    private static ExternalIdentity toDomain(JpaExternalIdentityEntity e) {
        return new ExternalIdentity(e.getId(), e.getUserId(), e.getProvider(), e.getSubject(), e.getDisplayName(),
                e.getProviderUsername(), e.getPhoneNumber(), e.getPhoneVerifiedAt(), e.getBotAccess(), e.getCreatedAt(),
                e.getUpdatedAt(), e.getLastLoginAt());
    }
}
