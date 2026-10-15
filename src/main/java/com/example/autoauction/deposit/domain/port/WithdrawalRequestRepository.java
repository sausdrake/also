package com.example.autoauction.deposit.domain.port;

import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;

import java.util.List;
import java.util.Optional;

/** F6: persistence port for {@link WithdrawalRequest}. Requests are never deleted. */
public interface WithdrawalRequestRepository {

    WithdrawalRequest save(WithdrawalRequest request);

    Optional<WithdrawalRequest> findById(Long id);

    /** Pessimistic row lock - every admin transition goes through this. */
    Optional<WithdrawalRequest> findByIdForUpdate(Long id);

    Optional<WithdrawalRequest> findByIdempotencyKey(String idempotencyKey);

    /** The user's own requests, newest first. */
    List<WithdrawalRequest> findByUserIdNewestFirst(Long userId);

    /** All requests (optionally of one status), newest first. */
    List<WithdrawalRequest> findAllNewestFirst(WithdrawalRequestStatus status);
}
