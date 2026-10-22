package com.example.autoauction.common.media;

import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.common.media.application.MediaAssetService;
import com.example.autoauction.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C-3: расширение сохранённого файла должно определяться исключительно
 * проверенным Content-Type, а не клиентским originalFilename.
 * H-7: удаление медиа разрешено только загрузившему файл пользователю
 * или администратору (Model A — uploader ownership + ADMIN override).
 */
@WebMvcTest(controllers = MediaController.class,
        properties = "media.upload.dir=target/media-controller-test-uploads")
@Import({TestSecurityConfig.class, MediaControllerTest.MethodSecurity.class})
@DisplayName("Тесты контроллера медиа — расширение (C-3) и авторизация удаления (H-7)")
class MediaControllerTest {

    /** The application enables method security (SecurityConfig); this web slice must too, or @PreAuthorize is inert. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurity {
    }

    /** SEC-04: uploads must carry the signature of their declared type; the payload after it is irrelevant here. */
    static byte[] bodyFor(String contentType) {
        byte[] head = switch (contentType) {
            case "image/jpeg" -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            case "image/png" -> new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            case "video/mp4" -> new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p'};
            default -> new byte[0];
        };
        byte[] body = java.util.Arrays.copyOf(head, head.length + 8);
        System.arraycopy("payload!".getBytes(), 0, body, head.length, 8);
        return body;
    }

    // TEST-03: the base URL the controller itself is configured with - never a separate literal.
    // A literal 8080 here never matched the 8081 this slice reads from application.properties,
    // so every delete request was refused at the prefix check before reaching what each test checks.
    @Value("${media.base-url}")
    private String baseUrl;

    @Value("${media.upload.dir}")
    private String uploadDir;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private MediaAssetService mediaAssetService;

    // ==================== TEST ISOLATION: uploads never reach the repository-root uploads/ ====================

    @Test
    @DisplayName("media.upload.dir этого теста находится внутри target/, а не в корневом uploads/")
    void uploadDir_isolatedUnderTarget() {
        Path dir = Paths.get(uploadDir).normalize();

        assertThat(dir.isAbsolute()).isFalse();
        assertThat(dir.getName(0).toString()).isEqualTo("target");
        assertThat(dir.getNameCount()).isGreaterThan(1);
        assertThat(uploadDir).isEqualTo("target/media-controller-test-uploads");
    }

    // ==================== C-3: EXTENSION DERIVATION (unchanged) ====================

    @Test
    @WithMockCustomUser
    @DisplayName("JPEG с filename=payload.html -> сохранённое расширение .jpg, не .html")
    void jpegWithHtmlFilename_extensionComesFromContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.html", "image/jpeg", bodyFor("image/jpeg"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".jpg")))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".html"))));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("PNG с filename=payload.html -> сохранённое расширение .png, не .html")
    void pngWithHtmlFilename_extensionComesFromContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.html", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".png")))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".html"))));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("PNG с filename=payload.svg -> сохранённое расширение .png, не .svg")
    void pngWithSvgFilename_extensionComesFromContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.svg", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".png")))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".svg"))));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("MP4 с filename=payload.html -> сохранённое расширение .mp4, не .html")
    void mp4WithHtmlFilename_extensionComesFromContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.html", "video/mp4", bodyFor("video/mp4"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".mp4")))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".html"))));
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource({
            "photo.jpg,  image/jpeg, .jpg",
            "photo.jpeg, image/jpeg, .jpg",
            "photo.png,  image/png,  .png",
            "video.mp4,  video/mp4,  .mp4",
    })
    @WithMockCustomUser
    @DisplayName("Обычные валидные имена файлов -> расширение всё равно берётся из Content-Type")
    void validFilenames_extensionStillComesFromContentType(String filename, String contentType, String expectedExt) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType, bodyFor(contentType));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(expectedExt)));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("Неподдерживаемый Content-Type -> отклонено по существующей семантике ошибок")
    void unsupportedContentType_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.html", "text/html", "<script>alert(1)</script>".getBytes());

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockCustomUser
    @DisplayName("Имя файла без расширения -> расширение всё равно берётся из Content-Type")
    void filenameWithoutExtension_extensionComesFromContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".png")));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("Множественные расширения photo.png.html -> сохранённое расширение .png")
    void multipleExtensionsInFilename_onlyContentTypeDecides() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png.html", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".png")))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".html"))));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("Регистр в имени файла (payload.HTML) не влияет на расширение")
    void filenameCaseVariation_doesNotAffectExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "payload.HTML", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".png")))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".HTML"))))
                .andExpect(jsonPath("$.publicUrl", not(containsString(".html"))));
    }

    @Test
    @WithMockCustomUser
    @DisplayName("originalFilename = null -> загрузка не зависит от имени файла")
    void nullOriginalFilename_uploadStillSucceeds() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", null, "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicUrl", endsWith(".png")));
    }

    // ==================== H-7: UPLOAD RECORDS UPLOADER ====================

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"DIAGNOSTIC"})
    @DisplayName("H-7: загрузка фиксирует MediaAsset с ID текущего пользователя")
    void upload_recordsMediaAssetWithCurrentUserId() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk());

        verify(mediaAssetService).recordUpload(anyString(), eq(7L));
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"DIAGNOSTIC"})
    @DisplayName("H-7: сбой сохранения MediaAsset -> загрузка НЕ возвращает 200")
    void upload_recordUploadFails_doesNotReturn200() throws Exception {
        doThrow(new RuntimeException("db connection refused: password=SuperSecret123"))
                .when(mediaAssetService).recordUpload(anyString(), eq(7L));

        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"DIAGNOSTIC"})
    @DisplayName("H-7: сбой сохранения MediaAsset -> клиенту не передаются детали исключения")
    void upload_recordUploadFails_doesNotLeakExceptionDetails() throws Exception {
        doThrow(new RuntimeException("db connection refused: password=SuperSecret123"))
                .when(mediaAssetService).recordUpload(anyString(), eq(7L));

        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(content().string(not(containsString("SuperSecret123"))))
                .andExpect(content().string(not(containsString("db connection refused"))));
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"DIAGNOSTIC"})
    @DisplayName("H-7: клиентский параметр uploaderId игнорируется — используется только принципал")
    void upload_clientSuppliedUploaderIdIgnored() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .param("uploaderId", "999"))
                .andExpect(status().isOk());

        verify(mediaAssetService).recordUpload(anyString(), eq(7L));
        verify(mediaAssetService, never()).recordUpload(anyString(), eq(999L));
    }

    // ==================== SEC-UPLOAD-01: WHO MAY UPLOAD ====================

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"USER"})
    @DisplayName("SEC-UPLOAD-01: an ordinary user cannot upload (403) - nothing written, nothing recorded")
    void upload_byOrdinaryUser_forbidden() throws Exception {
        long before = filesInUploadDir();
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isForbidden());

        verifyNoInteractions(mediaAssetService);
        assertThat(filesInUploadDir()).isEqualTo(before);
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"USER"})
    @DisplayName("SEC-UPLOAD-01: a forged role parameter or header does not help an ordinary user")
    void upload_byOrdinaryUser_withForgedRoleInput_forbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        mockMvc.perform(multipart("/api/media/upload").file(file).param("role", "ADMIN").header("X-Role", "ROLE_ADMIN"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    @WithMockCustomUser(userId = 55L, roles = {"DIAGNOSTIC"})
    @DisplayName("SEC-UPLOAD-01: a diagnostician may upload")
    void upload_byDiagnostic_allowed() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", bodyFor("image/jpeg"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk());

        verify(mediaAssetService).recordUpload(anyString(), eq(55L));
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("SEC-UPLOAD-01: an administrator may upload")
    void upload_byAdmin_allowed() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", bodyFor("image/jpeg"));

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isOk());

        verify(mediaAssetService).recordUpload(anyString(), eq(1L));
    }

    private long filesInUploadDir() throws java.io.IOException {
        Path dir = Paths.get(uploadDir);
        if (!java.nio.file.Files.exists(dir)) return 0;
        try (var files = java.nio.file.Files.list(dir)) {
            return files.count();
        }
    }

    // ==================== H-7: DELETE AUTHORIZATION ====================

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"USER"})
    @DisplayName("H-7: загрузивший может удалить собственный файл")
    void delete_ownFile_allowed() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/abc.png"))
                .andExpect(status().isNoContent());

        verify(mediaAssetService).authorizeDelete(eq("abc.png"), eq(7L), eq(false));
        verify(mediaAssetService).deleteRecord(eq("abc.png"));
    }

    @Test
    @WithMockCustomUser(userId = 999L, roles = {"USER"})
    @DisplayName("H-7: другой ROLE_USER не может удалить чужой файл -> 403")
    void delete_byUnrelatedUser_forbidden() throws Exception {
        doThrow(new SecurityException("not owner"))
                .when(mediaAssetService).authorizeDelete(anyString(), eq(999L), eq(false));

        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/abc.png"))
                .andExpect(status().isForbidden());

        verify(mediaAssetService, never()).deleteRecord(anyString());
    }

    @Test
    @WithMockCustomUser(userId = 55L, roles = {"DIAGNOSTIC"})
    @DisplayName("H-7: ROLE_DIAGNOSTIC без отношения к файлу -> 403")
    void delete_byUnrelatedDiagnostic_forbidden() throws Exception {
        doThrow(new SecurityException("not owner"))
                .when(mediaAssetService).authorizeDelete(anyString(), eq(55L), eq(false));

        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/abc.png"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("H-7: администратор может удалить чужой файл")
    void delete_byAdmin_allowed() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/abc.png"))
                .andExpect(status().isNoContent());

        verify(mediaAssetService).authorizeDelete(eq("abc.png"), eq(1L), eq(true));
    }

    @Test
    @DisplayName("H-7: неаутентифицированное удаление отклоняется")
    void delete_unauthenticated_rejected() throws Exception {
        // this slice has no authentication entry point (403); the real 401 is asserted in MediaUploadContentValidationIntegrationTest
        int status = mockMvc.perform(delete("/api/media").param("url", baseUrl + "/abc.png")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    @DisplayName("H-7: неаутентифицированная загрузка отклоняется")
    void upload_unauthenticated_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", bodyFor("image/png"));

        // this slice has no authentication entry point (403); the real 401 is asserted in MediaUploadContentValidationIntegrationTest
        int status = mockMvc.perform(multipart("/api/media/upload").file(file)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"USER"})
    @DisplayName("H-7: обычный пользователь не может удалить неучтённый (исторический) файл")
    void delete_unregisteredFile_byNonAdmin_forbidden() throws Exception {
        doThrow(new SecurityException("unregistered"))
                .when(mediaAssetService).authorizeDelete(anyString(), eq(7L), eq(false));

        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/historical.png"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("H-7: администратор может удалить неучтённый (исторический) файл")
    void delete_unregisteredFile_byAdmin_allowed() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/historical.png"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("H-7: удаление отсутствующего на диске файла по-прежнему возвращает 204")
    void delete_fileNotOnDisk_stillReturns204() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/does-not-exist-on-disk.png"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockCustomUser(userId = 7L, roles = {"USER"})
    @DisplayName("H-7: попытка path traversal отклоняется до вызова авторизации")
    void delete_pathTraversalAttempt_rejectedBeforeAuthorization() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/../../etc/passwd"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaAssetService);
    }

    // ==================== H-7: EMPTY/WHITESPACE STORAGE KEY ====================

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("H-7: url в точности равен baseUrl (пустой storageKey) -> 400 до авторизации")
    void delete_urlExactlyEqualsBaseUrl_rejectedBeforeAuthorization() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("H-7: url = baseUrl + \"/\" (пустой storageKey после нормализации) -> 400 до авторизации")
    void delete_urlIsBaseUrlWithTrailingSlash_rejectedBeforeAuthorization() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaAssetService);
    }

    @Test
    @WithMockCustomUser(userId = 1L, roles = {"ADMIN"})
    @DisplayName("H-7: storageKey из одних пробелов -> 400 до авторизации")
    void delete_whitespaceOnlyStorageKey_rejectedBeforeAuthorization() throws Exception {
        mockMvc.perform(delete("/api/media").param("url", baseUrl + "/   "))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mediaAssetService);
    }
}
