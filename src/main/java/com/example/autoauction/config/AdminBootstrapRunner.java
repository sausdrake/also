package com.example.autoauction.config;

import com.example.autoauction.user.application.AdminBootstrapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ADM-01: runs the first-admin bootstrap at startup only when ALSO_ADMIN_BOOTSTRAP=true. A
 * refused bootstrap (weak password, taken username...) fails the startup, so the operator sees it
 * at once; a database that already has an administrator is left untouched. See
 * docs/ops/admin-bootstrap.md.
 */
@Component
@ConditionalOnProperty(name = "ALSO_ADMIN_BOOTSTRAP", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapService adminBootstrapService;
    private final String username;
    private final String email;
    private final String password;

    public AdminBootstrapRunner(AdminBootstrapService adminBootstrapService,
                                @Value("${ALSO_ADMIN_USERNAME:}") String username,
                                @Value("${ALSO_ADMIN_EMAIL:}") String email,
                                @Value("${ALSO_ADMIN_PASSWORD:}") String password) {
        this.adminBootstrapService = adminBootstrapService;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        adminBootstrapService.bootstrap(username, email, password);
    }
}
