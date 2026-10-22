package com.example.autoauction.common.media;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MEDIA-PROD-01: through the real servlet container (MockMvc skips multipart limits). Spring's default 1 MB multipart
 * limit used to refuse an ordinary phone photo before MediaController's own 10 MB check; now a 3 MB photo is stored
 * and an oversized one is a 413 with a plain message, not a 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("MEDIA-PROD-01: /api/media/upload size limit over real HTTP")
class MediaUploadSizeLimitHttpIntegrationTest {

    private static final String PASSWORD = "diag-password-1";

    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String diagnosticToken() throws Exception {
        Role role = roleRepository.findByName("ROLE_DIAGNOSTIC")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_DIAGNOSTIC", "ROLE_DIAGNOSTIC")));
        String name = "mlim" + System.nanoTime();
        userRepository.save(new User(null, name, name + "@test.com", passwordEncoder.encode(PASSWORD), true, Set.of(role)));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return objectMapper.readTree(r.body()).get("accessToken").asText();
    }

    private static byte[] png(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) 0x11);
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }

    private HttpResponse<String> upload(String token, byte[] content) throws Exception {
        String boundary = "b" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/media/upload"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a 3 MB photo is accepted (was refused by the 1 MB default)")
    void threeMegabytePhoto_accepted() throws Exception {
        HttpResponse<String> r = upload(diagnosticToken(), png(3 * 1024 * 1024));

        assertThat(r.statusCode()).as(r.body()).isBetween(200, 201);
        assertThat(objectMapper.readTree(r.body()).get("publicUrl").asText()).contains("/uploads/");
    }

    @Test
    @DisplayName("a photo over 10 MB: 413 with a plain message, not a 500")
    void oversizedPhoto_413() throws Exception {
        HttpResponse<String> r = upload(diagnosticToken(), png(10 * 1024 * 1024 + 512 * 1024));

        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).contains("Файл слишком большой");
    }
}
