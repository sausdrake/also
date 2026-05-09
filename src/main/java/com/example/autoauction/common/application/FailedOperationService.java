// common/application/FailedOperationService.java
package com.example.autoauction.common.application;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;

@Slf4j
@Service
public class FailedOperationService {

    private final FailedOperationRepository failedOperationRepository;

    public FailedOperationService(FailedOperationRepository failedOperationRepository) {
        this.failedOperationRepository = failedOperationRepository;
    }

    @Transactional
    public void saveFailedOperation(String operationType, Long userId, Long auctionId, Exception e) {
        try {
            FailedOperation failedOp = new FailedOperation(
                    operationType,
                    userId,
                    auctionId,
                    e.getMessage(),
                    getStackTrace(e),
                    OffsetDateTime.now()
            );
            failedOperationRepository.save(failedOp);
            log.info("Saved failed operation: type={}, userId={}, auctionId={}",
                    operationType, userId, auctionId);
        } catch (Exception ex) {
            log.error("Failed to save failed operation: {}", ex.getMessage(), ex);
        }
    }

    private String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}