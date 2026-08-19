// diagnostic/web/LegacyMediaMigrationSecurityTest.java
package com.example.autoauction.diagnostic.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MEDIA-3.3 review, item 6: HTTP-layer coverage for the new admin migration
 * endpoint. Mirrors SecurityAccessTest's already-working @SpringBootTest +
 * @AutoConfigureMockMvc + @WithMockUser convention (NOT @WebMvcTest - see
 * DiagnosticWorkControllerTest's pre-existing, unrelated failure for why
 * that slice is unreliable in this codebase for multi-collaborator
 * controllers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MEDIA-3.3 review: HTTP security for legacy media migration endpoint")
class LegacyMediaMigrationSecurityTest {

    private static final String ENDPOINT = "/api/v1/admin/diagnostic/requests/legacy-media/migrate";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Неаутентифицированный запрос - 401")
    void migrate_Unauthenticated_401() throws Exception {
        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("Не-админ (роль USER) - 403")
    void migrate_NonAdmin_403() throws Exception {
        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Не-админ (роль DIAGNOSTIC) - 403")
    void migrate_DiagnosticRole_403() throws Exception {
        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Админ - успешный вызов (200)")
    void migrate_Admin_200() throws Exception {
        mockMvc.perform(post(ENDPOINT))
                .andExpect(status().isOk());
    }
}
