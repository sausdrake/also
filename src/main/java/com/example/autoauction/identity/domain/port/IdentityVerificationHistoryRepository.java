package com.example.autoauction.identity.domain.port;

import com.example.autoauction.identity.domain.IdentityVerificationHistory;

import java.util.List;

/** Append-only by design - there is deliberately no update/delete method. */
public interface IdentityVerificationHistoryRepository {

    IdentityVerificationHistory save(IdentityVerificationHistory history);

    List<IdentityVerificationHistory> findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(Long identityVerificationId);
}
