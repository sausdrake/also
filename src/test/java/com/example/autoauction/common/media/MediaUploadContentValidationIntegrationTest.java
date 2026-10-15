package com.example.autoauction.common.media;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * SEC-04: an upload is stored only when its bytes really are the declared type (JPEG/PNG/MP4
 * signature), not just when the client says so; stored files are served with nosniff.
 */
@SpringBootTest(properties = {"media.upload.dir=target/sec04-uploads", "media.base-url=http://localhost/uploads"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-04: upload content must match its declared type")
class MediaUploadContentValidationIntegrationTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0};
    static final byte[] HTML = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;

    private MvcResult upload(byte[] bytes, String contentType) throws Exception {
        return mockMvc.perform(multipart("/api/media/upload")
                        .file(new MockMultipartFile("file", "f.bin", contentType, bytes))
                        .with(SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(9401L, "sec04", "USER"))))
                .andReturn();
    }

    private static long storedFiles() throws Exception {
        Path dir = Path.of("target/sec04-uploads");
        if (!Files.exists(dir)) return 0;
        try (var s = Files.list(dir)) { return s.count(); }
    }

    @Test
    void genuineFiles_accepted() throws Exception {
        assertThat(upload(JPEG, "image/jpeg").getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(PNG, "image/png").getResponse().getStatus()).isEqualTo(200);
        assertThat(upload(MP4, "video/mp4").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void disguisedOrMismatchedContent_refused_andNothingStored() throws Exception {
        long before = storedFiles();
        assertThat(upload(HTML, "image/jpeg").getResponse().getStatus()).as("HTML as JPEG").isEqualTo(400);
        assertThat(upload(HTML, "image/png").getResponse().getStatus()).as("HTML as PNG").isEqualTo(400);
        assertThat(upload(HTML, "video/mp4").getResponse().getStatus()).as("HTML as MP4").isEqualTo(400);
        assertThat(upload(PNG, "image/jpeg").getResponse().getStatus()).as("PNG declared as JPEG").isEqualTo(400);
        assertThat(upload(JPEG, "video/mp4").getResponse().getStatus()).as("JPEG declared as MP4").isEqualTo(400);
        assertThat(upload(new byte[]{(byte) 0xFF, (byte) 0xD8}, "image/jpeg").getResponse().getStatus()).as("truncated").isEqualTo(400);
        assertThat(upload(new byte[0], "image/jpeg").getResponse().getStatus()).as("empty").isEqualTo(400);
        byte[] fakePng = PNG.clone();
        fakePng[1] = 'X';   // right first byte, wrong signature
        assertThat(upload(fakePng, "image/png").getResponse().getStatus()).as("partial PNG signature").isEqualTo(400);
        assertThat(storedFiles()).as("nothing written for refused uploads").isEqualTo(before);
    }

    @Test
    void storedFile_servedWithNosniff() throws Exception {
        String body = upload(JPEG, "image/jpeg").getResponse().getContentAsString(StandardCharsets.UTF_8);
        String url = JsonPath.read(body, "$.publicUrl");
        String name = url.substring(url.lastIndexOf('/') + 1);

        var response = mockMvc.perform(get("/uploads/" + name)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getContentType()).startsWith("image/jpeg");
    }
}
