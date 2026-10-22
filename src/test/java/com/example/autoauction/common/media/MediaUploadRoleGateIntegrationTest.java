package com.example.autoauction.common.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-UPLOAD-01 through the real SecurityFilterChain (MVP certification sweep finding): the role check on
 * /api/media/upload happens before the body is parsed, so a USER is refused with 403 whatever it sends (a
 * non-multipart call used to reach the controller and fail with 500); staff sending no file get a 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-UPLOAD-01: /api/media/upload role gate in the filter chain")
class MediaUploadRoleGateIntegrationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0};

    @Autowired private MockMvc mockMvc;

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder json() {
        return post("/api/media/upload").contentType("application/json").content("{}");
    }

    private static MockHttpServletRequestBuilder file() {
        return multipart("/api/media/upload").file(new MockMultipartFile("file", "car.jpg", "image/jpeg", JPEG));
    }

    @Test
    @DisplayName("USER / SELLER: 403 for a JSON body and for a real file - never 500")
    void userRefusedBeforeParsing() throws Exception {
        assertThat(status(json().with(user("u").roles("USER")))).isEqualTo(403);
        assertThat(status(file().with(user("u").roles("USER")))).isEqualTo(403);
    }

    @Test
    @DisplayName("anonymous: 401")
    void anonymous() throws Exception {
        assertThat(status(json())).isEqualTo(401);
        assertThat(status(file())).isEqualTo(401);
    }

    @Test
    @DisplayName("ADMIN / DIAGNOSTIC without a file: 400, not 500")
    void staffWithoutFile() throws Exception {
        assertThat(status(json().with(user("a").roles("ADMIN")))).isEqualTo(400);
        assertThat(status(json().with(user("d").roles("DIAGNOSTIC")))).isEqualTo(400);
    }
}
