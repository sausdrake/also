package com.example.autoauction.diagnostic.media.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.diagnostic.media.application.DiagnosticMediaService;
import com.example.autoauction.diagnostic.media.web.dto.MediaReadUrlResponse;
import com.example.autoauction.diagnostic.media.web.dto.MediaResponse;
import com.example.autoauction.diagnostic.media.web.dto.UpdateCaptionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * MEDIA-3.2. ID-scoped diagnostic-media operations. Falls under
 * /api/v1/diagnostic/** in SecurityConfig -> authenticated() (not role-gated
 * to DIAGNOSTIC/ADMIN, matching DiagnosticRequestController's convention):
 * the service layer performs the actual owner/diagnostician/admin
 * fine-grained authorization, since a request's owner (buyer) must also be
 * able to read their own diagnostic's media.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diagnostic/media")
@RequiredArgsConstructor
@Tag(name = "Медиа диагноста", description = "Операции над отдельными медиафайлами диагностики")
@PreAuthorize("isAuthenticated()")
public class DiagnosticMediaController {

    private final DiagnosticMediaService mediaService;

    @PostMapping("/{mediaId}/finalize")
    @Operation(summary = "Завершить загрузку медиафайла")
    public ResponseEntity<MediaResponse> finalizeUpload(
            @PathVariable Long mediaId,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/v1/diagnostic/media/{}/finalize - user: {}", mediaId, currentUser.getUserId());
        return ResponseEntity.ok(mediaService.finalize(mediaId, currentUser.getUserId()));
    }

    @GetMapping("/{mediaId}/url")
    @Operation(summary = "Получить короткоживущую подписанную ссылку на медиафайл")
    public ResponseEntity<MediaReadUrlResponse> getReadUrl(
            @PathVariable Long mediaId,
            @CurrentUser UserPrincipal currentUser
    ) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(mediaService.getReadUrl(mediaId, currentUser.getUserId(), isAdmin));
    }

    @GetMapping("/{mediaId}/thumbnail-url")
    @Operation(summary = "Получить короткоживущую подписанную ссылку на превью видео")
    public ResponseEntity<MediaReadUrlResponse> getThumbnailReadUrl(
            @PathVariable Long mediaId,
            @CurrentUser UserPrincipal currentUser
    ) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(mediaService.getThumbnailReadUrl(mediaId, currentUser.getUserId(), isAdmin));
    }

    @DeleteMapping("/{mediaId}")
    @Operation(summary = "Удалить медиафайл (мягкое удаление)")
    public ResponseEntity<Void> delete(
            @PathVariable Long mediaId,
            @CurrentUser UserPrincipal currentUser
    ) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        mediaService.delete(mediaId, currentUser.getUserId(), isAdmin);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{mediaId}/caption")
    @Operation(summary = "Изменить подпись медиафайла")
    public ResponseEntity<MediaResponse> updateCaption(
            @PathVariable Long mediaId,
            @Valid @RequestBody UpdateCaptionRequest request,
            @CurrentUser UserPrincipal currentUser
    ) {
        return ResponseEntity.ok(mediaService.updateCaption(mediaId, currentUser.getUserId(), request.getCaption()));
    }
}
