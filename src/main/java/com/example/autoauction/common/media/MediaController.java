package com.example.autoauction.common.media;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.common.media.application.MediaAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/media")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MediaController {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "video/mp4"
    );
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final MediaAssetService mediaAssetService;

    @Value("${media.upload.dir:uploads}")
    private String uploadDir;

    // DEV-02: required, like everywhere else it is read - no fallback to a localhost URL
    @Value("${media.base-url}")
    private String baseUrl;

    /**
     * Загрузка файла напрямую (MVP).
     * В production заменить на presigned URL через S3/Cloudinary.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @CurrentUser UserPrincipal currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Недопустимый тип файла. Разрешены: JPEG, PNG, MP4"));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Файл превышает максимальный размер 10 МБ"));
        }
        if (!contentMatchesType(file, contentType)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Содержимое файла не соответствует заявленному типу"));
        }

        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);

            String extension = getExtension(contentType);
            String filename = UUID.randomUUID() + extension;
            Path dest = dir.resolve(filename);
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

            try {
                mediaAssetService.recordUpload(filename, currentUser.getUserId());
            } catch (RuntimeException e) {
                // Метаданные владельца не записались - нельзя вернуть клиенту успех
                // (H-7 не сможет защитить этот файл). Лучший доступный вариант для MVP -
                // откатить только что созданный файл и сообщить об ошибке; без этого он
                // остаётся на диске как неучтённый файл (тот же case, что и исторические
                // файлы до внедрения MediaAsset) - тоже безопасно, но хуже для пользователя.
                try {
                    Files.deleteIfExists(dest);
                } catch (IOException cleanupEx) {
                    log.warn("Не удалось откатить файл {} после сбоя записи MediaAsset", filename, cleanupEx);
                }
                throw e;
            }

            String publicUrl = baseUrl + "/" + filename;
            return ResponseEntity.ok(Map.of("publicUrl", publicUrl));

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Ошибка при загрузке файла"));
        }
    }

    /**
     * Удаление файла по публичному URL.
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(
            @RequestParam("url") String publicUrl,
            @CurrentUser UserPrincipal currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!publicUrl.startsWith(baseUrl)) {
            return ResponseEntity.badRequest().build();
        }
        String filename = publicUrl.substring(baseUrl.length()).replaceAll("^/+", "");
        // Защита от path traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        // Пустой/пробельный storageKey у Paths.get(uploadDir, filename) может
        // разрешиться в саму директорию загрузок, а не в файл внутри неё.
        if (filename.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(ROLE_ADMIN));
        mediaAssetService.authorizeDelete(filename, currentUser.getUserId(), isAdmin);

        try {
            Path file = Paths.get(uploadDir, filename);
            Files.deleteIfExists(file);
            mediaAssetService.deleteRecord(filename);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * SEC-04: the declared Content-Type comes from the client; the stored file is accepted only when
     * its first bytes carry that type's signature (JPEG FF D8 FF, PNG 89 'PNG' 0D 0A 1A 0A, MP4 an
     * ISO-BMFF 'ftyp' box at offset 4). Anything else - HTML, scripts, another image type - is refused
     * before a byte reaches the upload directory.
     */
    static boolean contentMatchesType(MultipartFile file, String contentType) {
        byte[] head;
        try (var in = file.getInputStream()) {
            head = in.readNBytes(12);
        } catch (IOException e) {
            return false;
        }
        return switch (contentType) {
            case "image/jpeg" -> head.length >= 3
                    && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "image/png" -> head.length >= 8
                    && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                    && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A;
            case "video/mp4" -> head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
            default -> false;
        };
    }

    /**
     * Расширение определяется исключительно по проверенному Content-Type,
     * а не по клиентскому originalFilename — иначе можно подменить
     * расширение сохранённого файла (например .html) при валидном MIME-типе.
     */
    private String getExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png"  -> ".png";
            case "video/mp4"  -> ".mp4";
            default -> "";
        };
    }
}
