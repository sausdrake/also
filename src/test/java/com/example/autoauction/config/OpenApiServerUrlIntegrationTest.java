package com.example.autoauction.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * DEV-01: the API description named http://localhost:8080 as its only server, so Swagger UI's
 * "Try it out" sent every request to a port nothing listens on (the backend runs on 8081). The
 * server now comes from the request the document was fetched with - whatever origin Swagger UI
 * was opened from - and no address is hard-coded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("DEV-01: the OpenAPI server URL is the origin the docs were requested from")
class OpenApiServerUrlIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private String apiDocs(String scheme, String host, int port) throws Exception {
        MvcResult r = mockMvc.perform(get("/api-docs").with(request -> {
            request.setScheme(scheme);
            request.setServerName(host);
            request.setServerPort(port);
            return request;
        })).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("local run: the only server is http://localhost:8081, never 8080")
    void localRun_serverIsTheBackendPort() throws Exception {
        String json = apiDocs("http", "localhost", 8081);
        List<String> urls = JsonPath.read(json, "$.servers[*].url");
        assertThat(urls).containsExactly("http://localhost:8081");
        assertThat(json).doesNotContain("localhost:8080");
    }

    @Test
    @DisplayName("Swagger UI loads the description from its own origin, so Try it out uses that origin")
    void swaggerUi_loadsDocsFromSameOrigin() throws Exception {
        MvcResult r = mockMvc.perform(get("/api-docs/swagger-config")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String url = JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.url");
        assertThat(url).as("relative - same origin as the UI").isEqualTo("/api-docs");
    }

    @Test
    @DisplayName("another origin gets its own URL - nothing is pinned to localhost")
    void otherOrigin_isReflected() throws Exception {
        String json = apiDocs("https", "api.example.test", 443);
        List<String> urls = JsonPath.read(json, "$.servers[*].url");
        assertThat(urls).containsExactly("https://api.example.test");
        assertThat(json).doesNotContain("localhost");
    }
}
