package com.example.autoauction.common.media;

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

@RestController
@RequestMapping("/api/media")
@PreAuthorize("isAuthenticated()")
public class MediaController {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "video/mp4"
    );
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    @Value("${media.upload.dir:uploads}")
    private String uploadDir;

    @Value("${media.base-url:http://localhost:8080/uploads}")
    private String baseUrl;

    /**
     * Загрузка файла напрямую (MVP).
     * В production заменить на presigned URL через S3/Cloudinary.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
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

            String extension = getExtension(file.getOriginalFilename(), contentType);
            String filename = UUID.randomUUID() + extension;
            Path dest = dir.resolve(filename);
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

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
    public ResponseEntity<Void> delete(@RequestParam("url") String publicUrl) {
        if (!publicUrl.startsWith(baseUrl)) {
            return ResponseEntity.badRequest().build();
        }
        String filename = publicUrl.substring(baseUrl.length()).replaceAll("^/+", "");
        // Защита от path traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Path file = Paths.get(uploadDir, filename);
            Files.deleteIfExists(file);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String getExtension(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png"  -> ".png";
            case "video/mp4"  -> ".mp4";
            default -> "";
        };
    }
}
