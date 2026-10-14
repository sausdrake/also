package com.example.autoauction.auth.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP-006: /h2-console/** is no longer a public (permitAll / JWT-skipped) path. H2 is a test-only
 * dependency, so in production the matcher never matched anything; the test profile does enable
 * the H2 console, which is exactly where the removal is observable: an anonymous request is now
 * rejected by the security chain instead of being let through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MVP-006: the H2 console is not a public path")
class H2ConsoleNotPublicApiIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("anonymous request to /h2-console is rejected with 401")
    void h2Console_anonymous_401() throws Exception {
        mockMvc.perform(get("/h2-console/")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/h2-console/login.jsp")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the remaining public paths stay public")
    void remainingPublicPaths_stayOpen() throws Exception {
        mockMvc.perform(get("/api/v1/public/auctions")).andExpect(status().isOk());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }
}
