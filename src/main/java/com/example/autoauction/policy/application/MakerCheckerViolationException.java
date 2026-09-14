package com.example.autoauction.policy.application;

/**
 * Locked Decision 1 (maker/checker): the admin who created/last-configured this draft attempted
 * to publish it themselves. Different ADMIN must publish; no approval workflow.
 */
public class MakerCheckerViolationException extends PolicyConflictException {

    public MakerCheckerViolationException(Long policyVersionId, Long callerAdminId) {
        super("MAKER_CHECKER_VIOLATION",
                "PolicyVersion " + policyVersionId + " не может быть опубликована создателем (admin "
                        + callerAdminId + ") - требуется публикация другим администратором (maker/checker)");
    }
}
