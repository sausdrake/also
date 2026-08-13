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

    @Value("${media.base-url:http://localhost:8080/uploads}")
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
