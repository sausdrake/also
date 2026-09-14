package com.example.autoauction.policy.application;

/**
 * Locked Decision 5 (stale draft protection): this draft's immutable lineage
 * ({@code basedOnPublishedVersionId}) no longer equals the current published PolicyVersion's id -
 * a different PolicyVersion was published after this draft was created. No rebase, no silent
 * republish of stale content; the only legitimate action is a fresh draft from the current
 * published policy.
 */
public class StalePolicyBaseException extends PolicyConflictException {

    public StalePolicyBaseException(Long policyVersionId, Long basedOnPublishedVersionId, Long currentPublishedId) {
        super("STALE_POLICY_BASE",
                "PolicyVersion " + policyVersionId + " не может быть опубликована - черновик основан на версии "
                        + basedOnPublishedVersionId + ", но текущая опубликованная версия - "
                        + currentPublishedId + ". Создайте новый черновик на основе текущей версии.");
    }
}
