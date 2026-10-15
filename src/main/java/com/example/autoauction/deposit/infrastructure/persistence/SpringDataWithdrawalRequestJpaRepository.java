package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataWithdrawalRequestJpaRepository extends JpaRepository<JpaWithdrawalRequestEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM JpaWithdrawalRequestEntity w WHERE w.id = :id")
    Optional<JpaWithdrawalRequestEntity> findByIdWithLock(@Param("id") Long id);

    Optional<JpaWithdrawalRequestEntity> findByIdempotencyKey(String idempotencyKey);

    List<JpaWithdrawalRequestEntity> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    List<JpaWithdrawalRequestEntity> findAllByOrderByCreatedAtDescIdDesc();

    List<JpaWithdrawalRequestEntity> findByStatusOrderByCreatedAtDescIdDesc(WithdrawalRequestStatus status);
}
