package com.example.autoauction.policy.domain.port;

import com.example.autoauction.policy.domain.PolicyVersion;

import java.util.Optional;

/**
 * Slice 1b-1: minimal port surface - create/persist a PolicyVersion, retrieve one, and
 * atomically claim its publication. Deliberately excludes update/delete (PolicyVersion has no
 * legitimate update path beyond publication) and any auction-selection query (that belongs to
 * Slice 1b-2).
 */
public interface PolicyVersionRepository {

    PolicyVersion save(PolicyVersion policyVersion);

    Optional<PolicyVersion> findById(Long id);

    /**
     * Atomically claims the publish transition for the PolicyVersion with the given id -
     * {@code UPDATE ... WHERE id = :id AND published = false}, checked via rows-affected, the
     * same claim-first idiom already used elsewhere in this codebase (e.g.
     * SellerDiagnosticCommitment's AWAITING_FINALIZATION -&gt; FINALIZED transition). Returns
     * {@code true} only if this call won the claim (the row existed and was not already
     * published); {@code false} otherwise - the caller is responsible for distinguishing "not
     * found" from "already published" if it needs to, by a subsequent findById.
     */
    boolean claimPublish(Long id);
}
