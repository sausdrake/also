package com.example.autoauction.deposit.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataDepositJpaRepository extends JpaRepository<JpaDepositEntity, Long> {
    Optional<JpaDepositEntity> findByUserId(Long userId);

    /** P1.5: pessimistic row lock, same idiom as {@code SpringDataPaymentJpaRepository.findByIdWithLock}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM JpaDepositEntity d WHERE d.userId = :userId")
    Optional<JpaDepositEntity> findByUserIdWithLock(@Param("userId") Long userId);
}