package com.example.autoauction.auth.infrastructure.persistence;

import com.example.autoauction.auth.domain.ExternalIdentityProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataExternalIdentityJpaRepository extends JpaRepository<JpaExternalIdentityEntity, Long> {

    Optional<JpaExternalIdentityEntity> findByProviderAndSubject(ExternalIdentityProvider provider, String subject);

    Optional<JpaExternalIdentityEntity> findByUserIdAndProvider(Long userId, ExternalIdentityProvider provider);
}
