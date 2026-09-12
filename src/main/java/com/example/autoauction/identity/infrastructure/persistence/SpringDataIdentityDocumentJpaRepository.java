package com.example.autoauction.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataIdentityDocumentJpaRepository extends JpaRepository<JpaIdentityDocumentEntity, Long> {

    Optional<JpaIdentityDocumentEntity> findByStorageKey(String storageKey);
}
