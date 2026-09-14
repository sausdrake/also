package com.example.autoauction.policy.application;

/**
 * Locked concurrency design, section C: two concurrent requests both attempted to configure the
 * SAME (policyVersionId, violationDefinitionId) pair for the first time (no prior row existed
 * for either to update). Exactly one insert can win the {@code uk_policy_version_violation}
 * unique constraint (V38); this is thrown for the loser, translating the resulting
 * {@link org.springframework.dao.DataIntegrityViolationException} into a structured, distinct
 * conflict - never silently retried as an overwrite of the winner's row. The loser must reload
 * (which will now find the winner's row) and retry via the existing-row update path.
 */
public class ViolationInsertConflictException extends PolicyConflictException {

    public ViolationInsertConflictException(Long policyVersionId, Long violationDefinitionId, Throwable cause) {
        super("VIOLATION_INSERT_CONFLICT",
                "Нарушение " + violationDefinitionId + " для PolicyVersion " + policyVersionId
                        + " уже было настроено другим администратором одновременно с этим запросом. "
                        + "Обновите черновик и попробуйте снова.");
        if (cause != null) {
            initCause(cause);
        }
    }
}
