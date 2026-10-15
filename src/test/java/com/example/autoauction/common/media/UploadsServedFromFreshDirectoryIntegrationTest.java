package com.example.autoauction.common.media;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * MEDIA-04: on a fresh server the upload directory does not exist when the application starts.
 * The /uploads resource location used to be registered without a trailing slash in that case,
 * so every uploaded file answered 404 until the next restart.
 */
@SpringBootTest(properties = {"media.upload.dir=target/media04-fresh-uploads", "media.base-url=http://localhost/uploads"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("MEDIA-04: uploads are served even when the directory did not exist at startup")
class UploadsServedFromFreshDirectoryIntegrationTest {

    private static final Path DIR = Path.of("target/media04-fresh-uploads");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @Autowired private MockMvc mockMvc;

    @BeforeAll
    static void noDirectoryBeforeStartup() throws Exception {
        FileSystemUtils.deleteRecursively(DIR);   // runs before this class's application context starts
    }

    @Test
    void uploadedFile_isServed() throws Exception {
        String body = mockMvc.perform(multipart("/api/media/upload")
                        .file(new MockMultipartFile("file", "f.jpg", "image/jpeg", JPEG))
                        .with(SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(9501L, "media04", "USER"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String url = JsonPath.read(body, "$.publicUrl");

        var response = mockMvc.perform(get("/uploads/" + url.substring(url.lastIndexOf('/') + 1))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(JPEG);
    }
}
