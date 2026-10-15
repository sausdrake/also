package com.example.autoauction.config;

import com.example.autoauction.user.application.AdminBootstrapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** ADM-01: the bootstrap runs only when explicitly switched on. */
@DisplayName("ADM-01: AdminBootstrapRunner activation")
class AdminBootstrapRunnerTest {

    private final AdminBootstrapService service = mock(AdminBootstrapService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AdminBootstrapService.class, () -> service)
            .withUserConfiguration(AdminBootstrapRunner.class);

    @Test
    void absentOrFalse_noRunner() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AdminBootstrapRunner.class));
        runner.withPropertyValues("ALSO_ADMIN_BOOTSTRAP=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AdminBootstrapRunner.class));
    }

    @Test
    void true_runnerDelegatesWithConfiguredValues() {
        runner.withPropertyValues("ALSO_ADMIN_BOOTSTRAP=true", "ALSO_ADMIN_USERNAME=ops.admin",
                        "ALSO_ADMIN_EMAIL=ops@also.example", "ALSO_ADMIN_PASSWORD=Adm01-strong-pass")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AdminBootstrapRunner.class);
                    ctx.getBean(AdminBootstrapRunner.class).run(new DefaultApplicationArguments());
                    verify(service).bootstrap("ops.admin", "ops@also.example", "Adm01-strong-pass");
                });
    }
}
