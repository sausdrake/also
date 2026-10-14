package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin template constructor reads and toggles {@code isActive}; the API must publish the
 * flag under exactly that name, so deactivating a template from the UI really deactivates it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Inspection template active flag - API contract")
class InspectionTemplateActiveFlagApiIntegrationTest {

    private static final String BASE = "/api/v1/admin/diagnostic/templates";

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(9101L, "admin", "ADMIN"));
    }

    @Test
    @DisplayName("isActive is published and a template can be deactivated and reactivated")
    void isActive_isPublishedAndToggles() throws Exception {
        String name = "Флаг-" + System.nanoTime();
        String created = mockMvc.perform(post(BASE).with(admin()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"vehicleType\":\"SEDAN\",\"isActive\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.active").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(created, "$.id");

        mockMvc.perform(put(BASE + "/" + id).with(admin()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"vehicleType\":\"SEDAN\",\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
        mockMvc.perform(get(BASE + "/" + id).with(admin())).andExpect(jsonPath("$.isActive").value(false));

        mockMvc.perform(put(BASE + "/" + id).with(admin()).contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"vehicleType\":\"SEDAN\",\"isActive\":true}"))
                .andExpect(jsonPath("$.isActive").value(true));
    }
}
