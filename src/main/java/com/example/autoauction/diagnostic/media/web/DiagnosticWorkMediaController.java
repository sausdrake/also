package com.example.autoauction.diagnostic.media.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.diagnostic.media.application.DiagnosticMediaService;
import com.example.autoauction.diagnostic.media.web.dto.CreateMediaIntentRequest;
import com.example.autoauction.diagnostic.media.web.dto.MediaIntentResponse;
import com.example.autoauction.diagnostic.media.web.dto.MediaResponse;
import com.example.autoauction.diagnostic.media.web.dto.ReorderMediaRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MEDIA-3.2. Same base path/role restriction as DiagnosticWorkController
 * (/api/v1/diagnostic/work/**, DIAGNOSTIC or ADMIN only, per SecurityConfig) -
 * a separate controller class deliberately, so DiagnosticWorkController
 * (MEDIA-3.1A/3.1B) is not touched.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diagnostic/work")
@RequiredArgsConstructor
@Tag(name = "Медиа диагноста", description = "Загрузка фото/видео для пунктов анкеты диагностики")
@PreAuthorize("hasAnyRole('DIAGNOSTIC', 'ADMIN')")
public class DiagnosticWorkMediaController {

    private final DiagnosticMediaService mediaService;

    @PostMapping("/{requestId}/items/{itemId}/media/intents")
    @Operation(summary = "Создать intent на загрузку медиафайла")
    public ResponseEntity<MediaIntentResponse> createIntent(
            @PathVariable Long requestId,
            @PathVariable Long itemId,
            @Valid @RequestBody CreateMediaIntentRequest request,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/v1/diagnostic/work/{}/items/{}/media/intents - diagnostic: {}",
                requestId, itemId, currentUser.getUserId());
        MediaIntentResponse response = mediaService.createIntent(
                requestId, itemId, currentUser.getUserId(),
                request.getMediaType(), request.getMimeType(), request.getSizeBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{requestId}/items/{itemId}/media")
    @Operation(summary = "Список медиафайлов пункта анкеты")
    public ResponseEntity<List<MediaResponse>> listMedia(
            @PathVariable Long requestId,
            @PathVariable Long itemId,
            @CurrentUser UserPrincipal currentUser
    ) {
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(mediaService.listForItem(requestId, itemId, currentUser.getUserId(), isAdmin));
    }

    @PatchMapping("/{requestId}/items/{itemId}/media/order")
    @Operation(summary = "Изменить порядок медиафайлов пункта анкеты (полная замена)")
    public ResponseEntity<Void> reorder(
            @PathVariable Long requestId,
            @PathVariable Long itemId,
            @Valid @RequestBody ReorderMediaRequest request,
            @CurrentUser UserPrincipal currentUser
    ) {
        mediaService.reorder(requestId, itemId, currentUser.getUserId(), request.getMediaIds());
        return ResponseEntity.ok().build();
    }
}
