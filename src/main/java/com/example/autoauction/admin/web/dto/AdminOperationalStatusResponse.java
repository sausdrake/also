package com.example.autoauction.admin.web.dto;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Phase 73 (G-N4): aggregate, read-only operational signals for the admin-only status endpoint.
 * Deliberately contains only counts/timestamps derived from already-existing audit tables and
 * the in-memory {@link SchedulerHealthSignal} - no amounts, no KYC/document data, no storage
 * URLs, no JWTs, no credentials, no raw exception messages or stack traces, no arbitrary
 * repository data. See {@code AdminOperationalStatusController} for the authorization boundary.
 */
@Schema(description = "Агрегированные операционные сигналы (только для администратора)")
public record AdminOperationalStatusResponse(
        List<SchedulerStatus> schedulers,
        int openReconciliationCaseCount
) {

    @Schema(description = "Состояние одного планировщика (по последнему тику)")
    public record SchedulerStatus(
            String schedulerId,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime lastFailureAt,
            boolean lastExecutionFailed
    ) {
        public static SchedulerStatus from(String schedulerId, SchedulerHealthSignal.SchedulerState state) {
            return new SchedulerStatus(schedulerId, state.lastSuccessAt(), state.lastFailureAt(), state.lastExecutionFailed());
        }
    }

    public static AdminOperationalStatusResponse assemble(Map<String, SchedulerHealthSignal.SchedulerState> schedulerStates,
                                                            int openReconciliationCaseCount) {
        List<SchedulerStatus> schedulers = schedulerStates.entrySet().stream()
                .map(e -> SchedulerStatus.from(e.getKey(), e.getValue()))
                .toList();
        return new AdminOperationalStatusResponse(schedulers, openReconciliationCaseCount);
    }
}
