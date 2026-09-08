package com.example.autoauction.policy.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataPolicyVersionCounterJpaRepository extends JpaRepository<JpaPolicyVersionCounterEntity, Integer> {

    /**
     * Same pessimistic-lock idiom as SpringDataAuctionJpaRepository.findByIdWithLock: holds a
     * row-level lock on the single counter row for the remainder of the caller's transaction,
     * serializing concurrent version-number allocation without locking the wider
     * policy_version table.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM JpaPolicyVersionCounterEntity c WHERE c.id = :id")
    Optional<JpaPolicyVersionCounterEntity> findByIdWithLock(@Param("id") Integer id);
}
