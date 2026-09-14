package com.example.autoauction.policy.application;

/**
 * Locked Decision 6 (review-to-publish freshness): the complete publishable configuration
 * (commission + all PolicyVersionViolation rows) changed since the checker last observed
 * {@code observedConfigurationRevision} - someone else edited this draft after the checker
 * reviewed it but before they published. No silent publication of unobserved changes; the
 * checker must reload and re-review before publishing.
 */
public class StalePolicyConfigurationException extends PolicyConflictException {

    public StalePolicyConfigurationException(Long policyVersionId, Long observedConfigurationRevision,
                                               Long currentConfigurationRevision) {
        super("STALE_POLICY_CONFIGURATION",
                "PolicyVersion " + policyVersionId + " не может быть опубликована - конфигурация изменилась "
                        + "после проверки (наблюдалась редакция " + observedConfigurationRevision
                        + ", текущая редакция - " + currentConfigurationRevision
                        + "). Обновите черновик и проверьте его заново перед публикацией.");
    }
}
