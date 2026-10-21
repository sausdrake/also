package com.example.autoauction.auth.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface SpringDataOidcAuthorizationRequestJpaRepository extends JpaRepository<JpaOidcAuthorizationRequestEntity, Long> {

    Optional<JpaOidcAuthorizationRequestEntity> findByStateHash(String stateHash);

    /** Single use: only the first callback flips consumed_at. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaOidcAuthorizationRequestEntity r SET r.consumedAt = :at WHERE r.id = :id AND r.consumedAt IS NULL")
    int markConsumed(@Param("id") Long id, @Param("at") OffsetDateTime at);

    @Modifying
    @Query("DELETE FROM JpaOidcAuthorizationRequestEntity r WHERE r.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") OffsetDateTime before);
}
