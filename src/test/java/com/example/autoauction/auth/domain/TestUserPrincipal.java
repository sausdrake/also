package com.example.autoauction.auth.domain;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class TestUserPrincipal extends UserPrincipal {

    private final Collection<? extends GrantedAuthority> authorities;

    public TestUserPrincipal(String username, String password, Collection<? extends GrantedAuthority> authorities, Long userId) {
        super(username, password, authorities, userId);
        this.authorities = authorities;
    }

    public static TestUserPrincipal createWithRoles(Long userId, String username, String... roles) {
        List<GrantedAuthority> authorities = List.of(roles)
                .stream()
                .map(role -> "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        return new TestUserPrincipal(username, "password", authorities, userId);
    }
}