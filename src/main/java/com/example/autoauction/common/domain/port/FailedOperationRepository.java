// common/domain/port/FailedOperationRepository.java
package com.example.autoauction.common.domain.port;

import com.example.autoauction.common.domain.FailedOperation;
import java.util.List;
import java.util.Optional;

public interface FailedOperationRepository {
    FailedOperation save(FailedOperation failedOperation);
    Optional<FailedOperation> findById(Long id);
    List<FailedOperation> findByStatus(String status);
    void deleteById(Long id);
}