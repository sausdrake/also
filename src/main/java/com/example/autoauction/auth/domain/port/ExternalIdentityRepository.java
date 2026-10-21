package com.example.autoauction.auth.domain.port;

import com.example.autoauction.auth.domain.ExternalIdentity;
import com.example.autoauction.auth.domain.ExternalIdentityProvider;

import java.util.Optional;

public interface ExternalIdentityRepository {

    Optional<ExternalIdentity> findByProviderAndSubject(ExternalIdentityProvider provider, String subject);

    Optional<ExternalIdentity> findByUserIdAndProvider(Long userId, ExternalIdentityProvider provider);

    /**
     * Inserts or updates. An insert that collides with UNIQUE(provider, subject) or UNIQUE(user_id, provider)
     * raises DataIntegrityViolationException - the caller resolves the race, never this repository.
     */
    ExternalIdentity save(ExternalIdentity identity);
}
