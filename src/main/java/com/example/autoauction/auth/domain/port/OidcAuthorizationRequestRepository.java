package com.example.autoauction.auth.domain.port;

import com.example.autoauction.auth.domain.OidcAuthorizationRequest;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface OidcAuthorizationRequestRepository {

    OidcAuthorizationRequest save(OidcAuthorizationRequest request);

    Optional<OidcAuthorizationRequest> findByStateHash(String stateHash);

    /** Atomically marks the request used; false when it already was (replayed / concurrent callback). */
    boolean markConsumed(Long id, OffsetDateTime at);

    /** Housekeeping: removes requests that expired before {@code before}. */
    int deleteExpiredBefore(OffsetDateTime before);
}
