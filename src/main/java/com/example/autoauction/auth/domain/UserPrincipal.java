package com.example.autoauction.auth.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

public class UserPrincipal extends User {

    private final Long userId;

    public UserPrincipal(
            String username,
            String password,
            Collection<? extends GrantedAuthority> authorities,
            Long userId
    ) {
        this(username, password, true, authorities, userId);
    }

    /**
     * SEC-01: {@code enabled} must carry the user's own active flag. Every check that relies on
     * {@link #isEnabled()} - the REST JWT filter, the STOMP CONNECT interceptor and login
     * authentication - is only as good as this value; the 4-argument constructor (always enabled)
     * is kept for callers that build a principal for an already-verified active user.
     */
    public UserPrincipal(
            String username,
            String password,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities,
            Long userId
    ) {
        super(username, password, enabled, true, true, true, authorities);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}