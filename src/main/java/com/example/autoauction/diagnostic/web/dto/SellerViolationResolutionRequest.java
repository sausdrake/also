package com.example.autoauction.diagnostic.web.dto;

import java.util.List;

/**
 * X3-E: admin selects only violation codes (by ViolationDefinition id) - never a percentage,
 * amount, or severity directly. The server resolves the actual penalty from the applicable
 * PolicyVersion snapshot (SellerDiagnosticCommitment.policyVersionId for this, the
 * diagnostic-stage trigger).
 */
public record SellerViolationResolutionRequest(List<Long> violationDefinitionIds) {
}
