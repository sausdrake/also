package com.example.autoauction.identity.application;

import com.example.autoauction.auth.domain.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * VIN-AUTH-01 (approved policy): who may see a vehicle's VIN on the public pages - enforced by the
 * server, never by the frontend.
 * <ul>
 *   <li>anonymous visitor - no;</li>
 *   <li>signed-in user whose identity is not VERIFIED (none, pending, rejected, revoked) - no;</li>
 *   <li>user with a VERIFIED identity - yes;</li>
 *   <li>staff: administrators and diagnosticians - yes (they work with the vehicle and its documents).</li>
 * </ul>
 * The answer is read from the current request's authentication, so a public endpoint (where a token
 * is optional) answers per viewer.
 */
@Component
public class VinDisclosurePolicy {

    private final VerifiedIdentityGate verifiedIdentityGate;

    public VinDisclosurePolicy(VerifiedIdentityGate verifiedIdentityGate) {
        this.verifiedIdentityGate = verifiedIdentityGate;
    }

    public boolean currentViewerMaySeeVin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return false;
        }
        for (GrantedAuthority authority : principal.getAuthorities()) {
            String role = authority.getAuthority();
            if ("ROLE_ADMIN".equals(role) || "ROLE_DIAGNOSTIC".equals(role)) {
                return true;
            }
        }
        return verifiedIdentityGate.isVerified(principal.getUserId());
    }
}
