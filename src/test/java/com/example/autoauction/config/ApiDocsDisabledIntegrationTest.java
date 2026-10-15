package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CFG-01: the two springdoc switches set in application-prod.properties really take the API
 * description and Swagger UI offline in this springdoc version (they stay permitAll in
 * SecurityConfig, so only springdoc itself can hide them).
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CFG-01: API docs and Swagger UI are offline with the production switches")
class ApiDocsDisabledIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void apiDocsAndSwaggerUi_notServed() throws Exception {
        for (String path : new String[]{"/api-docs", "/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isIn(404, 401, 403);
            assertThat(status).as(path).isNotEqualTo(200);
        }
    }
}
