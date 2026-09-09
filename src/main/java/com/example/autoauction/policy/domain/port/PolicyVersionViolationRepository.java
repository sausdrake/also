package com.example.autoauction.policy.domain.port;

import com.example.autoauction.policy.domain.PolicyVersionViolation;

import java.util.List;
import java.util.Optional;

/**
 * X3-E: persistence port for version-specific violation-penalty configuration.
 */
public interface PolicyVersionViolationRepository {

    /**
     * Insert (id == null) or update (id set) a single row. An update against a row whose
     * parent PolicyVersion is already published is rejected by the DB trigger added in V38 -
     * callers (PolicyVersionService.updateDraftViolation) must check publication status
     * themselves first for a clear application-level error, this is defense-in-depth only.
     */
    PolicyVersionViolation save(PolicyVersionViolation policyVersionViolation);

    List<PolicyVersionViolation> findByPolicyVersionId(Long policyVersionId);

    Optional<PolicyVersionViolation> findByPolicyVersionIdAndViolationDefinitionId(
            Long policyVersionId, Long violationDefinitionId);
}
