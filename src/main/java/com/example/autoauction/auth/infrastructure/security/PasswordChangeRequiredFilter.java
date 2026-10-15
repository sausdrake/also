package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.domain.port.PasswordChangeRequirementRepository;
import com.example.autoauction.auth.web.AccountPasswordController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AUTH-01: while a user still has the temporary password an administrator set, every authenticated
 * request except the password change answers 403 PASSWORD_CHANGE_REQUIRED - the account can bid,
 * withdraw or see nothing until the user has a password nobody else knows. Runs right after the JWT
 * filter. Deliberately not a Spring bean: it is added to the security chain only (SecurityConfig).
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    private final PasswordChangeRequirementRepository requirements;

    public PasswordChangeRequiredFilter(PasswordChangeRequirementRepository requirements) {
        this.requirements = requirements;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && !isPasswordChange(request)
                && requirements.isRequired(principal.getUserId())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":403,\"error\":\"" + CODE
                    + "\",\"message\":\"Смените временный пароль, чтобы продолжить\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Exact path only - any other spelling of it (suffixes, "//") stays blocked. */
    private static boolean isPasswordChange(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "POST".equals(request.getMethod()) && AccountPasswordController.CHANGE_PASSWORD_PATH.equals(path);
    }
}
