// common/application/FailedOperationServiceTest.java
package com.example.autoauction.common.application;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты FailedOperationService")
class FailedOperationServiceTest {

    @Mock
    private FailedOperationRepository failedOperationRepository;

    @InjectMocks
    private FailedOperationService failedOperationService;

    @Test
    @DisplayName("Сохранение failed операции при ошибке разморозки депозита")
    void saveFailedOperation_ShouldPersist() {
        // given
        RuntimeException testException = new RuntimeException("Database connection failed");
        when(failedOperationRepository.save(any(FailedOperation.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        failedOperationService.saveFailedOperation("UNFREEZE_DEPOSIT", 1L, 100L, testException);

        // then
        ArgumentCaptor<FailedOperation> captor = ArgumentCaptor.forClass(FailedOperation.class);
        verify(failedOperationRepository, times(1)).save(captor.capture());

        FailedOperation saved = captor.getValue();
        assertThat(saved.getOperationType()).isEqualTo("UNFREEZE_DEPOSIT");
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getAuctionId()).isEqualTo(100L);
        assertThat(saved.getErrorMessage()).contains("Database connection failed");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getStackTrace()).isNotNull();
    }
}