// common/domain/port/FailedOperationRepository.java
package com.example.autoauction.common.domain.port;

import com.example.autoauction.common.domain.FailedOperation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FailedOperationRepository {
    FailedOperation save(FailedOperation failedOperation);
    Optional<FailedOperation> findById(Long id);
    List<FailedOperation> findByStatus(String status);
    List<FailedOperation> findAll();
    void deleteById(Long id);

    /** PENDING rows whose nextRetryAt is null or has already passed - eligible for retry now. */
    List<FailedOperation> findRetryablePending(OffsetDateTime now);

    /**
     * Atomically transitions PENDING -&gt; PROCESSING for exactly this row, guarded by a
     * conditional UPDATE (WHERE status = PENDING), the same pattern already used by
     * DiagnosticRequestRepository.assignToDiagnostic/markAsCompleted. Returns true only if
     * THIS call won the claim - false means another worker already claimed it (or it is no
     * longer PENDING for any other reason), and the caller must not process it.
     */
    boolean claim(Long id);
}